package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.Appointment;
import com.example.apimedlink.model.AppointmentStatus;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.Patient;
import com.example.apimedlink.model.Reassignment;
import com.example.apimedlink.model.ReassignmentStatus;
import com.example.apimedlink.model.Slot;
import com.example.apimedlink.model.SlotStatus;
import com.example.apimedlink.repository.AbsenceRepository;
import com.example.apimedlink.repository.AppointmentRepository;
import com.example.apimedlink.repository.PatientRepository;
import com.example.apimedlink.repository.PractitionerRepository;
import com.example.apimedlink.repository.ReassignmentRepository;
import com.example.apimedlink.repository.SlotRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** US-29: a proposal changes nothing until both parties independently accept; the transfer is atomic. */
@Service
public class ReassignmentService {
    public enum Party { PATIENT, PRACTITIONER }
    public record SlotSummary(UUID id, UUID clinicId, String clinicName, UUID practitionerId,
            String firstName, String lastName, Instant startsAt, Instant endsAt) {}
    public record Proposal(UUID id, UUID appointmentId, SlotSummary original, SlotSummary target,
            ReassignmentStatus status, boolean patientAccepted, boolean practitionerAccepted) {}
    public record Candidate(UUID appointmentId, String patientFirstName, String patientLastName, SlotSummary original) {}

    private final ReassignmentRepository proposals;
    private final AppointmentRepository appointments;
    private final SlotRepository slots;
    private final PatientRepository patients;
    private final PractitionerRepository practitioners;
    private final AbsenceRepository absences;
    private final AuditService audit;
    private final AppointmentConfirmation confirmation;
    private final PractitionerIdentityReconciler practitionerIdentity;

    public ReassignmentService(ReassignmentRepository proposals, AppointmentRepository appointments,
            SlotRepository slots, PatientRepository patients, PractitionerRepository practitioners,
            AbsenceRepository absences, AuditService audit, AppointmentConfirmation confirmation,
            PractitionerIdentityReconciler practitionerIdentity) {
        this.proposals = proposals;
        this.appointments = appointments;
        this.slots = slots;
        this.patients = patients;
        this.practitioners = practitioners;
        this.absences = absences;
        this.audit = audit;
        this.confirmation = confirmation;
        this.practitionerIdentity = practitionerIdentity;
    }

    @Transactional(readOnly = true)
    public Page<Proposal> staffList(int page, int size) {
        return proposals.findAll(recent(page, size)).map(this::view);
    }

    @Transactional(readOnly = true)
    public Page<Proposal> mine(UUID caller, Party party, int page, int size) {
        PageRequest paging = recent(page, size);
        return (party == Party.PATIENT ? proposals.forPatient(caller, paging)
                : proposals.forPractitioner(caller, paging)).map(this::view);
    }

    @Transactional(readOnly = true)
    public Page<Candidate> candidates(UUID practitionerId, UUID clinicId, int page, int size) {
        return appointments.futureInClinic(practitionerId, clinicId, Instant.now(),
                PageRequest.of(page, size, Sort.by("slot.startsAt", "id"))).map(a -> {
                    Patient patient = patients.findById(a.getPatientId()).orElseThrow();
                    return new Candidate(a.getId(), patient.getFirstName(), patient.getLastName(), summary(a.getSlot()));
                });
    }

    @Transactional(readOnly = true)
    public Page<SlotSummary> targets(UUID appointmentId, int page, int size) {
        Appointment original = appointments.findById(appointmentId).orElseThrow(ReassignmentService::missing);
        if (!futureBooked(original, Instant.now())) {
            throw unavailable();
        }
        Slot source = original.getSlot();
        return slots.reassignmentTargets(source.getClinic().getId(), source.getPractitioner().getId(),
                original.getPatientId(), appointmentId, Instant.now(), PageRequest.of(page, size, Sort.by("startsAt", "id")))
                .map(ReassignmentService::summary);
    }

    @Transactional
    public Proposal propose(UUID staff, UUID appointmentId, UUID targetSlotId) {
        Appointment original = appointments.findForChangeById(appointmentId).orElseThrow(ReassignmentService::missing);
        Instant now = Instant.now();
        if (!futureBooked(original, now) || proposals.existsByAppointmentIdAndStatus(appointmentId, ReassignmentStatus.PENDING)) {
            throw new ApiException(HttpStatus.CONFLICT, "REASSIGNMENT_PENDING_OR_UNAVAILABLE",
                    "Ce rendez-vous est indisponible ou fait déjà l'objet d'une proposition.");
        }
        Slot target = slots.findForBookingById(targetSlotId).orElseThrow(ReassignmentService::missing);
        if (!validTarget(original, target, now)) {
            throw unavailable();
        }
        Reassignment proposal = proposals.saveAndFlush(new Reassignment(appointmentId, targetSlotId, staff, now));
        trace(proposal, AuditOutcome.SUCCESS);
        return view(proposal);
    }

    @Transactional
    public Proposal decide(UUID id, UUID caller, Party party, Boolean accepted) {
        Reassignment proposal = proposals.findForChangeById(id).orElseThrow(ReassignmentService::foreign);
        Appointment original = appointments.findForChangeById(proposal.getAppointmentId()).orElseThrow(ReassignmentService::foreign);
        // Read only the owner ID here. Loading the target entity before locking it could cache a stale FREE status.
        UUID entitled = party == Party.PATIENT ? original.getPatientId()
                : slots.practitionerOf(proposal.getTargetSlotId()).orElseThrow(ReassignmentService::foreign);
        if (!entitled.equals(caller)) {
            throw foreign();
        }
        if (accepted == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Indiquez votre décision.");
        }
        if (proposal.getStatus() != ReassignmentStatus.PENDING) {
            return view(proposal);
        }
        boolean alreadyAccepted = party == Party.PATIENT ? proposal.getPatientAcceptedAt() != null
                : proposal.getPractitionerAcceptedAt() != null;
        if (alreadyAccepted) {
            if (!accepted) {
                throw new ApiException(HttpStatus.CONFLICT, "DECISION_ALREADY_RECORDED", "Votre décision est déjà enregistrée.");
            }
            return view(proposal);
        }
        Instant now = Instant.now();
        if (!accepted) {
            proposal.close(ReassignmentStatus.DECLINED, now);
            trace(proposal, AuditOutcome.SUCCESS);
            return view(proposal);
        }
        if (party == Party.PATIENT) {
            proposal.patientAccepts(now);
        } else {
            proposal.practitionerAccepts(now);
        }
        trace(proposal, AuditOutcome.SUCCESS);
        if (proposal.acceptedByBoth()) {
            transfer(proposal, original, caller);
        }
        return view(proposal);
    }

    private void transfer(Reassignment proposal, Appointment original, UUID caller) {
        patients.lockForBooking(original.getPatientId()).orElseThrow(ReassignmentService::foreign);
        UUID oldId = original.getSlot().getId();
        UUID targetId = proposal.getTargetSlotId();
        // Same appointment -> patient -> sorted slot lock order as patient rescheduling.
        Slot oldSlot;
        Slot target;
        if (oldId.compareTo(targetId) < 0) {
            oldSlot = slots.findForBookingById(oldId).orElseThrow();
            target = slots.findForBookingById(targetId).orElseThrow();
        } else {
            target = slots.findForBookingById(targetId).orElseThrow();
            oldSlot = slots.findForBookingById(oldId).orElseThrow();
        }
        Instant now = Instant.now();
        if (!futureBooked(original, now) || !validTarget(original, target, now)) {
            proposal.close(ReassignmentStatus.FAILED, now);
            trace(proposal, AuditOutcome.FAILURE);
            return;
        }
        original.setStatus(AppointmentStatus.CANCELLED);
        original.setCancelledAt(now);
        oldSlot.setStatus(SlotStatus.CLOSED);
        appointments.saveAndFlush(original);
        slots.saveAndFlush(oldSlot);
        target.setStatus(SlotStatus.BOOKED);
        Appointment moved = new Appointment(target, original.getPatientId(),
                proposal.getProposedBy() == null ? caller : proposal.getProposedBy());
        moved.setBookedByStaff(true);
        appointments.saveAndFlush(moved);
        proposal.close(ReassignmentStatus.DONE, now);
        audit.recordWithin(AuditAction.APPOINTMENT_RESCHEDULED, "APPOINTMENT", moved.getId().toString(), AuditOutcome.SUCCESS);
        trace(proposal, AuditOutcome.SUCCESS);
        confirmation.onBooked(moved);
    }

    private boolean validTarget(Appointment original, Slot target, Instant now) {
        Slot source = original.getSlot();
        return target.getStatus() == SlotStatus.FREE && target.getStartsAt().isAfter(now)
                && source.getClinic().getId().equals(target.getClinic().getId())
                && !source.getPractitioner().getId().equals(target.getPractitioner().getId())
                && target.getPractitioner().isBookingEnabled()
                && practitionerIdentity.eligibleNow(target.getPractitioner().getId())
                && practitioners.clinicActive(target.getPractitioner().getId(), target.getClinic().getId())
                && !absences.covers(target.getPractitioner().getId(), target.getStartsAt(), target.getEndsAt())
                && !appointments.overlapsOther(original.getPatientId(), original.getId(), target.getStartsAt(), target.getEndsAt());
    }

    private static boolean futureBooked(Appointment appointment, Instant now) {
        return appointment.getStatus() == AppointmentStatus.BOOKED && appointment.getSlot().getStartsAt().isAfter(now);
    }

    private Proposal view(Reassignment proposal) {
        Appointment original = appointments.findById(proposal.getAppointmentId()).orElseThrow();
        Slot target = slots.findById(proposal.getTargetSlotId()).orElseThrow();
        return new Proposal(proposal.getId(), proposal.getAppointmentId(), summary(original.getSlot()), summary(target),
                proposal.getStatus(), proposal.getPatientAcceptedAt() != null, proposal.getPractitionerAcceptedAt() != null);
    }

    private static SlotSummary summary(Slot slot) {
        return new SlotSummary(slot.getId(), slot.getClinic().getId(), slot.getClinic().getName(),
                slot.getPractitioner().getId(), slot.getPractitioner().getFirstName(), slot.getPractitioner().getLastName(),
                slot.getStartsAt(), slot.getEndsAt());
    }

    private static PageRequest recent(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }

    private void trace(Reassignment proposal, AuditOutcome outcome) {
        audit.recordWithin(AuditAction.ACCOUNT_UPDATED, "APPOINTMENT_REASSIGNMENT", proposal.getId().toString(), outcome);
    }

    private static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Le rendez-vous ou le créneau n'existe pas.");
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.CONFLICT, "REASSIGNMENT_UNAVAILABLE", "Ce transfert n'est plus possible.");
    }

    private static AccessDeniedException foreign() {
        return new AccessDeniedException("Not the caller's reassignment");
    }
}
