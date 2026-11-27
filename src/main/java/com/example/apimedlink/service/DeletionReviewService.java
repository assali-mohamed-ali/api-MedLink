package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.DeletionRequest;
import com.example.apimedlink.model.DeletionStatus;
import com.example.apimedlink.model.Patient;
import com.example.apimedlink.repository.AccountOperation;
import com.example.apimedlink.repository.DeletionRequestRepository;
import com.example.apimedlink.repository.PatientRepository;
import com.example.apimedlink.repository.SessionRevocationStore;
import com.example.apimedlink.security.CurrentUser;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * US-16, the staff's side: the administrative staff list the pending deletion requests and decide each one. A medical
 * record is kept for {@code retention-years} (20 by default) after the patient's last consultation, and until the
 * patient is 28 when they were a minor at that consultation (INPDP's recommendation for health data, to be confirmed
 * by the code owner): while that period runs, a request cannot be approved (409), only refused with a note.
 *
 * <p>The workflow keeps four steps apart. <b>Request</b>: the patient asks (PENDING). <b>Decision</b>: staff refuse with
 * a note the patient reads, or approve. <b>Closure</b>: an approval closes the account in the same transaction as the
 * decision: the local session revocation is written and the Keycloak disable is registered as a durable account
 * operation, carried out right after the commit (or by the worker after an outage), then the patient is told by mail.
 * Closure is reversible by staff (re-enabling the account). <b>Anonymisation and purge</b> are irreversible and are not
 * implemented: they wait for the approved retention policy. Nothing medical and no audit row is deleted here. Every
 * decision is audited in its transaction. Takes and returns entities and plain values, never a DTO.
 */
@Service
public class DeletionReviewService {

    private static final Logger log = LoggerFactory.getLogger(DeletionReviewService.class);

    static final String RESOURCE = "PATIENT_DELETION_DECISION";
    static final int MAJORITY = 18;
    static final int MINOR_RETENTION_AGE = 28;

    private final DeletionRequestRepository requests;
    private final PatientRepository patients;
    private final PatientDeletionService patientSide;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final SessionRevocationStore revocations;
    private final AccountOperationRunner operations;
    private final AdminUserService accounts;
    private final MailService mail;
    private final TransactionTemplate transaction;
    private final int retentionYears;

    public DeletionReviewService(DeletionRequestRepository requests, PatientRepository patients,
            PatientDeletionService patientSide, AuditService audit, CurrentUser currentUser,
            SessionRevocationStore revocations, AccountOperationRunner operations, AdminUserService accounts,
            MailService mail, PlatformTransactionManager transactionManager,
            @Value("${app.patient-deletion.retention-years:20}") int retentionYears) {
        this.requests = requests;
        this.patients = patients;
        this.patientSide = patientSide;
        this.audit = audit;
        this.currentUser = currentUser;
        this.revocations = revocations;
        this.operations = operations;
        this.accounts = accounts;
        this.mail = mail;
        this.transaction = new TransactionTemplate(transactionManager);
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
     * {@code RETENTION_APPLIES}) while the retention period runs; otherwise it closes the account (see the class
     * comment). 404 for an unknown request, 409 for one already decided or while another change of the account is
     * pending ({@code OPERATION_PENDING}: nothing is decided).
     */
    public DeletionReview decide(UUID patientId, DeletionStatus decision, String note) {
        if (decision == DeletionStatus.PENDING) {
            throw new IllegalArgumentException("A decision is APPROVED or REJECTED");
        }
        Decided decided = transaction.execute(status -> decideWithin(patientId, decision, note));
        if (decided.closure() != null) {
            try {
                operations.runRegistered(decided.closure(), accounts::execute);
            } catch (RuntimeException outage) {
                // The decision and the local revocation are committed; the worker finishes the Keycloak side
                log.warn("Account closure of patient {} is pending after an approved deletion request ({})",
                        patientId, outage.getClass().getSimpleName());
            }
            try {
                mail.sendAccountClosedAfterDeletionRequest(decided.email(), decided.review().patient().getFirstName());
            } catch (RuntimeException mailFailed) {
                log.warn("Closure notice for patient {} not sent ({})", patientId, mailFailed.getClass().getSimpleName());
            }
        }
        return decided.review();
    }

    private record Decided(DeletionReview review, UUID closure, String email) {}

    private Decided decideWithin(UUID patientId, DeletionStatus decision, String note) {
        patients.lockForBooking(patientId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", "Cette demande n'existe pas."));
        Patient patient = patients.findById(patientId).orElseThrow();
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
        UUID closure = null;
        if (decision == DeletionStatus.APPROVED) {
            closure = operations.registerWithin(AccountOperation.statusChange(patientId, false));
            revocations.revoke(patientId);
            request.closeAccount(now);
        }
        audit.recordWithin(AuditAction.ACCOUNT_UPDATED, RESOURCE, patientId.toString(), AuditOutcome.SUCCESS);
        return new Decided(review, closure, patient.getEmail());
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
