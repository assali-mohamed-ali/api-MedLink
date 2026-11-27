package com.example.apimedlink.service;

import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.DeletionRequest;
import com.example.apimedlink.repository.DeletionRequestRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-16: a patient asks for their data to be deleted, and can take the request back. Asking only records the request
 * (and keeps the account working, so the patient can cancel): <b>nothing is deleted by this service</b>. The
 * irreversible purge, after the grace period, is not implemented until the retention period of medical data is confirmed
 * (docs/product-decisions.md). Asking twice keeps the first request. Every change is audited in its transaction.
 */
@Service
public class PatientDeletionService {

    static final String RESOURCE = "PATIENT_DELETION_REQUEST";

    private final DeletionRequestRepository requests;
    private final AuditService audit;
    private final Duration grace;

    public PatientDeletionService(DeletionRequestRepository requests, AuditService audit,
            @Value("${app.patient-deletion.grace-days:30}") long graceDays) {
        this.requests = requests;
        this.audit = audit;
        this.grace = Duration.ofDays(graceDays);
    }

    /** The patient's request, if any. */
    @Transactional(readOnly = true)
    public Optional<DeletionRequest> current(UUID patientId) {
        return requests.findById(patientId);
    }

    /** Records the request (the first one is kept when there already is one) and returns it. */
    @Transactional
    public DeletionRequest request(UUID patientId) {
        Optional<DeletionRequest> existing = requests.findById(patientId);
        if (existing.isPresent()) {
            return existing.get();
        }
        DeletionRequest saved = requests.save(new DeletionRequest(patientId, Instant.now()));
        audit.recordWithin(AuditAction.ACCOUNT_UPDATED, RESOURCE, patientId.toString(), AuditOutcome.SUCCESS);
        return saved;
    }

    /** Takes the request back; nothing to do (and nothing audited) when there is none. */
    @Transactional
    public void cancel(UUID patientId) {
        if (requests.existsById(patientId)) {
            requests.deleteById(patientId);
            audit.recordWithin(AuditAction.ACCOUNT_UPDATED, RESOURCE, patientId.toString(), AuditOutcome.SUCCESS);
        }
    }

    /** When the grace period of a request ends: the earliest moment a purge could run. */
    public Instant scheduledFor(DeletionRequest request) {
        return request.getRequestedAt().plus(grace);
    }
}
