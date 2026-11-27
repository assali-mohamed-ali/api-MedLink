package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.Absence;
import com.example.apimedlink.model.Appointment;
import com.example.apimedlink.model.AppointmentStatus;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.Patient;
import com.example.apimedlink.model.Practitioner;
import com.example.apimedlink.model.Slot;
import com.example.apimedlink.model.SlotStatus;
import com.example.apimedlink.repository.AbsenceRepository;
import com.example.apimedlink.repository.AppointmentRepository;
import com.example.apimedlink.repository.PatientRepository;
import com.example.apimedlink.repository.SlotRepository;
import com.example.apimedlink.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * US-21: a practitioner declares an absence. Declaring it closes the free slots it covers; the booked appointments it
 * covers are not touched until the practitioner decides, one by one: cancel, or move to the same time next week (same
 * clinic, a free slot must exist then and not be in an absence). The patient is told by mail after the change is
 * committed; a mail that fails never blocks the decision. Takes and returns entities and plain values, never a DTO.
 */
@Service
public class AbsenceService {

    private static final Logger log = LoggerFactory.getLogger(AbsenceService.class);

    static final String RESOURCE_ABSENCE = "PRACTITIONER_ABSENCE";
    static final Duration MAX_ABSENCE = Duration.ofDays(90);
    static final Duration NEXT_WEEK = Duration.ofDays(7);

    private final AbsenceRepository absences;
    private final AppointmentRepository appointments;
    private final SlotRepository slots;
    private final PatientRepository patients;
    private final UserRepository users;
    private final AuditService audit;
    private final MailService mail;

    public AbsenceService(AbsenceRepository absences, AppointmentRepository appointments, SlotRepository slots,
            PatientRepository patients, UserRepository users, AuditService audit, MailService mail) {
        this.absences = absences;
        this.appointments = appointments;
        this.slots = slots;
        this.patients = patients;
        this.users = users;
        this.audit = audit;
        this.mail = mail;
    }

    @Transactional
    public DeclaredAbsence declare(UUID practitionerId, Instant from, Instant to, String reason) {
        Instant now = Instant.now();
        if (!to.isAfter(from) || !to.isAfter(now) || Duration.between(from, to).compareTo(MAX_ABSENCE) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains champs sont invalides.",
                    Map.of("endsAt", "La fin doit être après le début et dans le futur, pour 90 jours au plus."));
        }
        Absence absence = absences.save(new Absence(practitionerId, from, to,
                reason == null || reason.isBlank() ? null : reason.trim(), now));
        List<Slot> free = absences.freeSlotsOverlapping(practitionerId, from, to);
        free.forEach(slot -> slot.setStatus(SlotStatus.CLOSED));
        audit.recordWithin(AuditAction.ACCOUNT_UPDATED, RESOURCE_ABSENCE, absence.getId().toString(), AuditOutcome.SUCCESS);
        return new DeclaredAbsence(absence, free.size(), toDecide(practitionerId, absence));
    }

    @Transactional(readOnly = true)
    public List<Absence> mine(UUID practitionerId) {
        return absences.findByPractitionerIdOrderByStartsAtDesc(practitionerId);
    }

    /** The absence's booked appointments still waiting for a decision. 404 for an absence that is not the caller's. */
    @Transactional(readOnly = true)
    public List<AbsenceAppointment> pending(UUID practitionerId, UUID absenceId) {
        Absence absence = absences.findByIdAndPractitionerId(absenceId, practitionerId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "ABSENCE_NOT_FOUND", "Cette absence n'existe pas."));
        return toDecide(practitionerId, absence);
    }

    /**
     * Cancels the appointment, or moves it to the same time next week. 403 for an appointment that is not the
     * caller's; 409 {@code NOT_BOOKED}, {@code NOT_IN_ABSENCE}, {@code NO_SLOT_NEXT_WEEK} or {@code PATIENT_BUSY}.
     */
    @Transactional
    public AbsenceDecision decide(UUID practitionerId, UUID appointmentId, AbsenceDecision.Action action) {
        Appointment appointment = appointments.findForChangeById(appointmentId)
                .orElseThrow(() -> new AccessDeniedException("No appointment " + appointmentId + " for the caller"));
        Slot slot = appointment.getSlot();
        if (!slot.getPractitioner().getId().equals(practitionerId)) {
            throw new AccessDeniedException("Appointment " + appointmentId + " is not the caller's");
        }
        if (appointment.getStatus() != AppointmentStatus.BOOKED) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_BOOKED", "Ce rendez-vous n'est plus réservé.");
        }
        if (!absences.covers(practitionerId, slot.getStartsAt(), slot.getEndsAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_IN_ABSENCE", "Ce rendez-vous n'est pas pendant une absence.");
        }
        UUID patientId = appointment.getPatientId();
        Practitioner practitioner = slot.getPractitioner();
        String practitionerName = "Dr " + practitioner.getFirstName() + " " + practitioner.getLastName();
        String clinic = slot.getClinic().getName();
        Instant oldStart = slot.getStartsAt();
        String to = users.findById(patientId).map(user -> user.getEmail()).orElse(null);
        String firstName = patients.findById(patientId).map(Patient::getFirstName).orElse(null);
        Instant now = Instant.now();

        if (action == AbsenceDecision.Action.CANCEL) {
            Slot locked = slots.findForBookingById(slot.getId()).orElseThrow();
            appointment.setStatus(AppointmentStatus.CANCELLED);
            appointment.setCancelledAt(now);
            locked.setStatus(SlotStatus.CLOSED);
            audit.recordWithin(AuditAction.APPOINTMENT_CANCELLED, "APPOINTMENT", appointmentId.toString(),
                    AuditOutcome.SUCCESS);
            afterCommit(() -> notify(appointmentId, to, () -> mail.sendAppointmentCancelledByPractitioner(
                    to, firstName, practitionerName, clinic, oldStart)));
            return new AbsenceDecision(action, appointmentId, null);
        }

        Instant newStart = oldStart.plus(NEXT_WEEK);
        UUID newSlotId = absences.freeSlotAt(practitionerId, slot.getClinic().getId(), newStart)
                .orElseThrow(AbsenceService::noSlotNextWeek);
        // Both slots locked in one order (lower id first), as a patient's move does, so two moves never deadlock
        Slot oldLocked;
        Slot newLocked;
        if (slot.getId().compareTo(newSlotId) <= 0) {
            oldLocked = slots.findForBookingById(slot.getId()).orElseThrow();
            newLocked = slots.findForBookingById(newSlotId).orElseThrow();
        } else {
            newLocked = slots.findForBookingById(newSlotId).orElseThrow();
            oldLocked = slots.findForBookingById(slot.getId()).orElseThrow();
        }
        if (newLocked.getStatus() != SlotStatus.FREE || absences.covers(practitionerId, newLocked.getStartsAt(),
                newLocked.getEndsAt())) {
            throw noSlotNextWeek();
        }
        appointment.setStatus(AppointmentStatus.CANCELLED);
        appointment.setCancelledAt(now);
        oldLocked.setStatus(SlotStatus.CLOSED);
        appointments.saveAndFlush(appointment);
        slots.saveAndFlush(oldLocked);
        if (appointments.existsOverlapping(patientId, AppointmentStatus.BOOKED, newLocked.getStartsAt(),
                newLocked.getEndsAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "PATIENT_BUSY",
                    "Le patient a déjà un rendez-vous à ce moment-là : annulez celui-ci à la place.");
        }
        newLocked.setStatus(SlotStatus.BOOKED);
        Appointment moved = appointments.saveAndFlush(new Appointment(newLocked, patientId, practitionerId));
        audit.recordWithin(AuditAction.APPOINTMENT_RESCHEDULED, "APPOINTMENT", moved.getId().toString(),
                AuditOutcome.SUCCESS);
        afterCommit(() -> notify(appointmentId, to, () -> mail.sendAppointmentPostponedByPractitioner(
                to, firstName, practitionerName, clinic, oldStart, newStart)));
        return new AbsenceDecision(action, appointmentId, moved);
    }

    private List<AbsenceAppointment> toDecide(UUID practitionerId, Absence absence) {
        List<Appointment> booked = absences.bookedOverlapping(practitionerId, absence.getStartsAt(), absence.getEndsAt());
        Map<UUID, Patient> byId = patients.findAllById(booked.stream().map(Appointment::getPatientId).distinct().toList())
                .stream().collect(Collectors.toMap(Patient::getId, Function.identity()));
        return booked.stream().map(appointment -> new AbsenceAppointment(appointment, byId.get(appointment.getPatientId())))
                .toList();
    }

    private static ApiException noSlotNextWeek() {
        return new ApiException(HttpStatus.CONFLICT, "NO_SLOT_NEXT_WEEK",
                "Aucun créneau libre à la même heure la semaine suivante : ouvrez-en un ou annulez le rendez-vous.");
    }

    /** A mail that cannot be sent never blocks the decision; logged by appointment id only. */
    private void notify(UUID appointmentId, String to, Runnable send) {
        if (to == null || to.isBlank()) {
            log.warn("Absence notice not sent for appointment {}: no e-mail", appointmentId);
            return;
        }
        try {
            send.run();
        } catch (RuntimeException failed) {
            log.warn("Absence notice not sent for appointment {}: {}", appointmentId, failed.getClass().getSimpleName());
        }
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
