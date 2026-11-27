package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.DeletionRequest;
import com.example.apimedlink.model.DeletionStatus;
import com.example.apimedlink.repository.DeletionRequestRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import com.example.apimedlink.repository.PatientRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-16, the patient's side: a patient asks for their data to be deleted, reads the state of the request, and can take
 * it back while it is still pending. Asking only records the request: <b>nothing is deleted by this service</b>. The
 * administrative staff decide (DeletionReviewService). Asking twice keeps the first open request; a refused request can
 * be made again. Every change is audited in its transaction.
 */
@Service
public class PatientDeletionService {

    static final String RESOURCE = "PATIENT_DELETION_REQUEST";

    private final DeletionRequestRepository requests;
    private final AuditService audit;
    private final PatientRepository patients;
    private final Duration responseTime;

    public PatientDeletionService(DeletionRequestRepository requests, AuditService audit, PatientRepository patients,
            @Value("${app.patient-deletion.response-days:30}") long responseDays) {
        this.requests = requests;
        this.audit = audit;
        this.patients = patients;
        this.responseTime = Duration.ofDays(responseDays);
    }

    @Transactional(readOnly = true)
    public Optional<DeletionRequest> current(UUID patientId) {
        return requests.findById(patientId);
    }

    /** Records the request; an open (pending or approved) one is kept, a refused one is replaced by a new request. */
    @Transactional
    public DeletionRequest request(UUID patientId) {
        lockPatient(patientId);
        Optional<DeletionRequest> existing = requests.findById(patientId);
        if (existing.isPresent() && existing.get().getStatus() != DeletionStatus.REJECTED) {
            return existing.get();
        }
        existing.ifPresent(old -> {
            requests.delete(old);
            requests.flush();
        });
        DeletionRequest saved = requests.save(new DeletionRequest(patientId, Instant.now().truncatedTo(ChronoUnit.MICROS)));
        audit.recordWithin(AuditAction.ACCOUNT_UPDATED, RESOURCE, patientId.toString(), AuditOutcome.SUCCESS);
        return saved;
    }

    /** Takes a pending request back; nothing to do when there is none; 409 once staff have decided. */
    @Transactional
    public void cancel(UUID patientId) {
        lockPatient(patientId);
        Optional<DeletionRequest> existing = requests.findById(patientId);
        if (existing.isEmpty()) {
            return;
        }
        if (existing.get().getStatus() != DeletionStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_ALREADY_DECIDED",
                    "Cette demande a déjà été traitée : elle ne peut plus être annulée.");
        }
        requests.delete(existing.get());
        audit.recordWithin(AuditAction.ACCOUNT_UPDATED, RESOURCE, patientId.toString(), AuditOutcome.SUCCESS);
    }

    private void lockPatient(UUID patientId) {
        patients.lockForBooking(patientId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "PATIENT_NOT_FOUND", "Ce patient n'existe pas."));
    }

    /** The date by which staff must answer: one month after the request (the INPDP's time limit for a rights request). */
    public Instant dueAt(DeletionRequest request) {
        return request.getRequestedAt().plus(responseTime);
    }
}
