package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.ConsultationReport;
import com.example.apimedlink.model.Practitioner;
import com.example.apimedlink.repository.AppointmentRepository;
import com.example.apimedlink.repository.ConsultationReportRepository;
import com.example.apimedlink.repository.PractitionerRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consultation reports (MLink-32): written and read by the practitioners a patient is attached to, the record's rule
 * (MLink-31: a BOOKED appointment, past or upcoming). The text is sealed before it reaches the database and opened
 * only here (ReportCipher). Takes and returns entities and plain values, never a DTO. Never logs a text.
 */
@Service
public class ConsultationReportService {

    /** The longest report taken: about four pages, plenty for a consultation (to confirm with the team). */
    static final int MAX_LENGTH = 10_000;

    private static final String CONTENT = "content";

    /** The audit trail's names (32.4): a written report by its id, a read by the patient's id. Ids only. */
    static final String RESOURCE_REPORT = "CONSULTATION_REPORT";
    static final String RESOURCE_PATIENT_REPORTS = "PATIENT_REPORTS";

    private final ConsultationReportRepository reports;
    private final AppointmentRepository appointments;
    private final PractitionerRepository practitioners;
    private final ReportCipher cipher;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;
    private final AuditService audit;

    public ConsultationReportService(ConsultationReportRepository reports, AppointmentRepository appointments,
            PractitionerRepository practitioners, ReportCipher cipher, PlatformTransactionManager transactionManager,
            AuditService audit) {
        this.reports = reports;
        this.appointments = appointments;
        this.practitioners = practitioners;
        this.cipher = cipher;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.audit = audit;
    }

    /**
     * Saves a report of the caller on an attached patient. The attachment is checked first, so a stranger learns
     * nothing, not even the text rules. The report is saved in one transaction: if it fails, nothing is kept and the
     * caller gets REPORT_SAVE_FAILED, so the front keeps the draft and offers to try again (32.6). The REPORT_CREATED
     * row is part of that transaction (32.4): a report is never kept without its trace. MLink-32 follow-up: only once a
     * consultation with the patient has started; before, 409 CONSULTATION_NOT_STARTED and nothing is saved.
     */
    public ReadableReport create(UUID practitionerId, UUID patientId, String content) {
        requireAttached(practitionerId, patientId);
        if (!consultationStarted(practitionerId, patientId)) {
            throw new ApiException(HttpStatus.CONFLICT, "CONSULTATION_NOT_STARTED",
                    "Vous pourrez écrire le compte-rendu une fois la consultation commencée.");
        }
        validate(content);
        UUID id = UUID.randomUUID();
        ConsultationReport report = new ConsultationReport(id, patientId, practitionerId, cipher.encrypt(id, patientId, content));
        ConsultationReport saved;
        try {
            saved = transaction.execute(status -> {
                ConsultationReport written = reports.saveAndFlush(report);
                audit.recordWithin(AuditAction.REPORT_CREATED, RESOURCE_REPORT, id.toString(), AuditOutcome.SUCCESS);
                return written;
            });
        } catch (DataAccessException | TransactionException failed) {
            // The cause stays out of the message: it could describe the database
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT_SAVE_FAILED",
                    "Le compte-rendu n'a pas pu être enregistré. Réessayez dans un instant.");
        }
        return new ReadableReport(saved.getId(), saved.getCreatedAt(), content);
    }

    /**
     * The patient's reports, newest first, whoever of their practitioners wrote them. The REPORT_VIEWED row is written
     * before they leave (32.4): a read that cannot be traced is not answered. The read transaction ends first and gives
     * its connection back, then the row is written in its own (review on #123): a list never holds two connections.
     */
    public List<ReadableReport> list(UUID practitionerId, UUID patientId) {
        List<ReadableReport> found = readOnly.execute(status -> {
            requireAttached(practitionerId, patientId);
            return reports.findByPatientIdOrderByCreatedAtDesc(patientId).stream()
                    .map(report -> new ReadableReport(report.getId(), report.getCreatedAt(),
                            cipher.decrypt(report.getId(), report.getPatientId(), report.getContentEncrypted())))
                    .toList();
        });
        audit.record(AuditAction.REPORT_VIEWED, RESOURCE_PATIENT_REPORTS, patientId.toString(), AuditOutcome.SUCCESS);
        return found;
    }

    /**
     * MLink-32 follow-up: the patient's own reports, newest first, each with the practitioner who wrote it (name and
     * specialty). Traced like a practitioner's read (REPORT_VIEWED on the patient), after the read transaction.
     */
    public List<PatientReport> mine(UUID patientId) {
        List<PatientReport> found = readOnly.execute(status -> {
            List<ConsultationReport> written = reports.findByPatientIdOrderByCreatedAtDesc(patientId);
            Map<UUID, Practitioner> authors = practitioners
                    .findAllById(written.stream().map(ConsultationReport::getPractitionerId).distinct().toList())
                    .stream().collect(Collectors.toMap(Practitioner::getId, Function.identity()));
            return written.stream().map(report -> {
                Practitioner author = authors.get(report.getPractitionerId());
                return new PatientReport(report.getId(), report.getCreatedAt(),
                        cipher.decrypt(report.getId(), report.getPatientId(), report.getContentEncrypted()),
                        author.getFirstName(), author.getLastName(), author.getSpecialty().getName());
            }).toList();
        });
        audit.record(AuditAction.REPORT_VIEWED, RESOURCE_PATIENT_REPORTS, patientId.toString(), AuditOutcome.SUCCESS);
        return found;
    }

    /** MLink-32 follow-up: a consultation with the patient has started, so a report may be written. */
    public boolean consultationStarted(UUID practitionerId, UUID patientId) {
        return appointments.existsStartedByPractitionerAndPatient(practitionerId, patientId, Instant.now());
    }

    /**
     * Not attached, unknown or malformed: the record's 403 (audited as a refusal by the access-denied handler). Also
     * called by {@code ReportWriteGuard} before a write's body is read (review on #123).
     */
    public void requireAttached(UUID practitionerId, UUID patientId) {
        if (!appointments.existsByPractitionerAndPatient(practitionerId, patientId)) {
            throw new AccessDeniedException("Patient not attached to the practitioner");
        }
    }

    private static void validate(String content) {
        String error = null;
        if (content == null || content.isBlank()) {
            error = "Le compte-rendu est vide.";
        } else if (content.length() > MAX_LENGTH) {
            error = "Le compte-rendu dépasse 10 000 caractères.";
        }
        if (error != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains champs sont invalides.",
                    Map.of(CONTENT, error));
        }
    }
}
