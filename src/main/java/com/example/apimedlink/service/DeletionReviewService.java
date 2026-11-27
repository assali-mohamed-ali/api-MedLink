package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.DeletionRequest;
import com.example.apimedlink.model.DeletionStatus;
import com.example.apimedlink.model.Patient;
import com.example.apimedlink.repository.DeletionRequestRepository;
import com.example.apimedlink.repository.PatientRepository;
import com.example.apimedlink.security.CurrentUser;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-16, the staff's side: the administrative staff list the pending deletion requests and decide each one. A medical
 * record is kept for {@code retention-years} (20 by default) after the patient's last consultation, and until the
 * patient is 28 when they were a minor at that consultation (INPDP's recommendation for health data, to be confirmed
 * by the code owner): while that period runs, a request cannot be approved (409), only refused with a note. Approving
 * a request <b>deletes nothing</b>: the purge is not implemented yet. Every decision is audited in its transaction.
 * Takes and returns entities and plain values, never a DTO.
 */
@Service
public class DeletionReviewService {

    static final String RESOURCE = "PATIENT_DELETION_DECISION";
    static final int MAJORITY = 18;
    static final int MINOR_RETENTION_AGE = 28;

    private final DeletionRequestRepository requests;
    private final PatientRepository patients;
    private final PatientDeletionService patientSide;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final int retentionYears;

    public DeletionReviewService(DeletionRequestRepository requests, PatientRepository patients,
            PatientDeletionService patientSide, AuditService audit, CurrentUser currentUser,
            @Value("${app.patient-deletion.retention-years:20}") int retentionYears) {
        this.requests = requests;
        this.patients = patients;
        this.patientSide = patientSide;
        this.audit = audit;
        this.currentUser = currentUser;
        this.retentionYears = retentionYears;
    }

    /** The pending requests, oldest first. */
    @Transactional(readOnly = true)
    public List<DeletionReview> pending() {
        return requests.findByStatusOrderByRequestedAtAsc(DeletionStatus.PENDING).stream()
                .map(request -> review(request, Instant.now()))
                .toList();
    }

    /**
     * Staff's decision on a pending request. REJECTED needs a note (the patient reads it). APPROVED is refused (409
     * {@code RETENTION_APPLIES}) while the retention period runs. 404 for an unknown request, 409 for one already decided.
     */
    @Transactional
    public DeletionReview decide(UUID patientId, DeletionStatus decision, String note) {
        if (decision == DeletionStatus.PENDING) {
            throw new IllegalArgumentException("A decision is APPROVED or REJECTED");
        }
        patients.lockForBooking(patientId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", "Cette demande n'existe pas."));
        DeletionRequest request = requests.findById(patientId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", "Cette demande n'existe pas."));
        if (request.getStatus() != DeletionStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_ALREADY_DECIDED", "Cette demande a déjà été traitée.");
        }
        Instant now = Instant.now();
        DeletionReview review = review(request, now);
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        if (decision == DeletionStatus.REJECTED && cleanNote == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains champs sont invalides.",
                    Map.of("note", "Un motif est obligatoire pour refuser une demande."));
        }
        if (decision == DeletionStatus.APPROVED && review.retainedUntil() != null && review.retainedUntil().isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "RETENTION_APPLIES",
                    "Le dossier doit être conservé : la durée de conservation n'est pas terminée.");
        }
        request.decide(decision, currentUser.id(), now, cleanNote);
        audit.recordWithin(AuditAction.ACCOUNT_UPDATED, RESOURCE, patientId.toString(), AuditOutcome.SUCCESS);
        return review;
    }

    private DeletionReview review(DeletionRequest request, Instant now) {
        Patient patient = patients.findById(request.getPatientId()).orElseThrow();
        Instant lastAppointment = requests.lastAppointmentStart(patient.getId(), now);
        Instant lastReport = requests.lastReportAt(patient.getId());
        Instant last = latest(lastAppointment, lastReport);
        return new DeletionReview(request, patient, patientSide.dueAt(request), last, retainedUntil(patient, last));
    }

    private Instant retainedUntil(Patient patient, Instant lastConsultation) {
        if (lastConsultation == null) {
            return null;
        }
        LocalDate last = lastConsultation.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate until = last.plusYears(retentionYears);
        LocalDate birth = patient.getBirthDate();
        if (birth != null && birth.plusYears(MAJORITY).isAfter(last)) {
            LocalDate minorUntil = birth.plusYears(MINOR_RETENTION_AGE);
            if (minorUntil.isAfter(until)) {
                until = minorUntil;
            }
        }
        return until.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }
}
