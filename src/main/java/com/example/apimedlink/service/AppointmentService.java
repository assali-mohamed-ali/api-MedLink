package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.Appointment;
import com.example.apimedlink.model.AppointmentStatus;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.Slot;
import com.example.apimedlink.model.SlotStatus;
import com.example.apimedlink.repository.AppointmentRepository;
import com.example.apimedlink.repository.PatientRepository;
import com.example.apimedlink.repository.SlotRepository;
import com.example.apimedlink.security.OwnershipGuard;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A patient's appointments (epic MLink-8). Takes and returns entities and plain values, never a DTO. */
@Service
public class AppointmentService {

    static final String SLOT_TAKEN = "Ce créneau n'est plus disponible. Choisissez-en un autre.";
    static final String PATIENT_BUSY = "Vous avez déjà un rendez-vous à ce moment-là.";

    /** V13: one BOOKED appointment per slot at most. */
    static final String ONE_ACTIVE_PER_SLOT = "appointments_one_active_per_slot";

    private final AppointmentRepository appointments;
    private final SlotRepository slots;
    private final PatientRepository patients;
    private final OwnershipGuard ownership;
    private final AuditService audit;
    /** H-03: no cancel less than this before the start (app.appointments.cancel-deadline-hours). */
    private final Duration cancelDeadline;

    public AppointmentService(AppointmentRepository appointments, SlotRepository slots, PatientRepository patients,
            OwnershipGuard ownership, AuditService audit,
            @Value("${app.appointments.cancel-deadline-hours}") long cancelDeadlineHours) {
        this.appointments = appointments;
        this.slots = slots;
        this.patients = patients;
        this.ownership = ownership;
        this.audit = audit;
        this.cancelDeadline = Duration.ofHours(cancelDeadlineHours);
    }

    /**
     * MLink-106: the holder cancels their appointment, up to the H-03 deadline before it starts. An unknown id is refused
     * like someone else's appointment (never tell whether it exists); a cancelled one stays as it is. One transaction,
     * the appointment and then its slot locked: the appointment becomes CANCELLED and the slot FREE again, and the
     * APPOINTMENT_CANCELLED audit row is written in that same transaction (review on #120). Nothing changed, no row.
     */
    @Transactional
    public void cancel(UUID appointmentId) {
        Appointment appointment = appointments.findForChangeById(appointmentId)
                .orElseThrow(() -> new AccessDeniedException("No appointment " + appointmentId + " for the caller"));
        ownership.check(appointment.getPatientId());
        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            return;
        }
        Slot slot = slots.findForBookingById(appointment.getSlot().getId()).orElseThrow();
        Instant now = Instant.now();
        checkCancelDeadline(slot, now);
        appointment.setStatus(AppointmentStatus.CANCELLED);
        appointment.setCancelledAt(now);
        slot.setStatus(SlotStatus.FREE);
        audit.recordWithin(AuditAction.APPOINTMENT_CANCELLED, "APPOINTMENT", appointmentId.toString(),
                AuditOutcome.SUCCESS);
    }

    /**
     * MLink-107: the holder moves their appointment to another free slot, with the access rules and the H-03 deadline
     * of a cancel. One transaction: the old appointment is cancelled and its slot freed first, then the new slot is
     * booked; when the new slot is taken (409) or unknown (404), the whole move is rolled back and the patient keeps the
     * appointment they had. Both slots are locked in one order (lower id first), so two crossed moves never deadlock.
     * The new appointment's APPOINTMENT_RESCHEDULED audit row is part of the move (review on #120).
     */
    @Transactional
    public Appointment reschedule(UUID appointmentId, UUID newSlotId) {
        Appointment old = appointments.findForChangeById(appointmentId)
                .orElseThrow(() -> new AccessDeniedException("No appointment " + appointmentId + " for the caller"));
        ownership.check(old.getPatientId());
        if (old.getStatus() == AppointmentStatus.CANCELLED) {
            throw new ApiException(HttpStatus.CONFLICT, "APPOINTMENT_CANCELLED",
                    "Ce rendez-vous est annulé : réservez un nouveau créneau.");
        }
        // MLink-167: the patient before the slots, as a booking does
        patients.lockForBooking(old.getPatientId());
        UUID oldSlotId = old.getSlot().getId();
        Slot oldSlot;
        Slot newSlot;
        if (oldSlotId.compareTo(newSlotId) <= 0) {
            oldSlot = slots.findForBookingById(oldSlotId).orElseThrow();
            newSlot = slotToBook(newSlotId);
        } else {
            newSlot = slotToBook(newSlotId);
            oldSlot = slots.findForBookingById(oldSlotId).orElseThrow();
        }
        Instant now = Instant.now();
        checkCancelDeadline(oldSlot, now);
        old.setStatus(AppointmentStatus.CANCELLED);
        old.setCancelledAt(now);
        oldSlot.setStatus(SlotStatus.FREE);
        appointments.saveAndFlush(old);
        slots.saveAndFlush(oldSlot);
        Appointment moved = bookLocked(newSlot, old.getPatientId(), now);
        audit.recordWithin(AuditAction.APPOINTMENT_RESCHEDULED, "APPOINTMENT", moved.getId().toString(),
                AuditOutcome.SUCCESS);
        return moved;
    }

    private Slot slotToBook(UUID slotId) {
        return slots.findForBookingById(slotId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Ce créneau n'existe pas."));
    }

    /** H-03: no cancel (nor move) less than {@code cancelDeadline} before the appointment starts. */
    private void checkCancelDeadline(Slot slot, Instant now) {
        if (Duration.between(now, slot.getStartsAt()).compareTo(cancelDeadline) < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CANCEL_DEADLINE_PASSED",
                    "Ce rendez-vous ne peut plus être annulé moins de " + cancelDeadline.toHours()
                            + " heures avant son début.");
        }
    }

    /** MLink-108: a page of the patient's own appointments, latest first, cancelled ones included. */
    @Transactional(readOnly = true)
    public Page<Appointment> mine(UUID patientId, int page, int size) {
        Sort latestFirst = Sort.by(Sort.Direction.DESC, "slot.startsAt").and(Sort.by(Sort.Direction.DESC, "id"));
        return appointments.findByPatientId(patientId, PageRequest.of(page, size, latestFirst));
    }

    /**
     * MLink-102: the patient books a free slot that has not started, for themselves ({@code patient_id} =
     * {@code booked_by} = the caller). One transaction: the slot row is locked, the appointment is created and the
     * slot becomes BOOKED, so a second booking of the same slot waits, then sees it taken. MLink-104 (review on #120):
     * the audit row is written in this same transaction, so a booking is never kept without its trace, and a failed
     * audit write rolls the booking back instead of answering 500 for a slot that stays booked.
     */
    @Transactional
    public Appointment book(UUID patientId, UUID slotId) {
        // A patient account made outside registration (e.g. in the Keycloak console) has no profile to book with
        // MLink-167: the patient's row is locked first, so two bookings of one patient never pass the overlap
        // check together; no row: an account made outside registration (e.g. in the Keycloak console)
        if (patients.lockForBooking(patientId).isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PATIENT_PROFILE_REQUIRED",
                    "Complétez votre profil patient avant de réserver.");
        }
        Appointment appointment = bookLocked(slotToBook(slotId), patientId, Instant.now());
        // Ids only (the caller is taken from the session by AuditService)
        audit.recordWithin(AuditAction.APPOINTMENT_BOOKED, "APPOINTMENT", appointment.getId().toString(),
                AuditOutcome.SUCCESS);
        return appointment;
    }

    /** Books a slot already locked by the caller's transaction, for the patient (patient_id = booked_by). */
    private Appointment bookLocked(Slot slot, UUID patientId, Instant now) {
        if (slot.getStatus() != SlotStatus.FREE || !slot.getStartsAt().isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "SLOT_TAKEN", SLOT_TAKEN);
        }
        // MLink-167: one place at a time. A move has already cancelled (and flushed) the appointment it replaces,
        // so that one does not count; a cancelled appointment never does.
        if (appointments.existsOverlapping(patientId, AppointmentStatus.BOOKED, slot.getStartsAt(), slot.getEndsAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "PATIENT_BUSY", PATIENT_BUSY);
        }
        slot.setStatus(SlotStatus.BOOKED);
        Appointment appointment;
        try {
            appointment = appointments.saveAndFlush(new Appointment(slot, patientId, patientId));
        } catch (DataIntegrityViolationException e) {
            // MLink-103: the unique index on a slot's active appointment (V13) is the last guard against a double
            // booking; losing to it is the same "taken" answer as above, never a server error
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains(ONE_ACTIVE_PER_SLOT)) {
                throw new ApiException(HttpStatus.CONFLICT, "SLOT_TAKEN", SLOT_TAKEN);
            }
            throw e;
        }
        // Loaded here, inside the transaction: the controller maps them after it has ended
        Hibernate.initialize(slot.getPractitioner());
        Hibernate.initialize(slot.getClinic());
        return appointment;
    }
}
