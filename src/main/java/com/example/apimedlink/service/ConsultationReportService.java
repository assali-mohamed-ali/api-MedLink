package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.ConsultationReport;
import com.example.apimedlink.repository.AppointmentRepository;
import com.example.apimedlink.repository.ConsultationReportRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
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

    private final ConsultationReportRepository reports;
    private final AppointmentRepository appointments;
    private final ReportCipher cipher;
    private final TransactionTemplate transaction;

    public ConsultationReportService(ConsultationReportRepository reports, AppointmentRepository appointments,
            ReportCipher cipher, PlatformTransactionManager transactionManager) {
        this.reports = reports;
        this.appointments = appointments;
        this.cipher = cipher;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Saves a report of the caller on an attached patient. The attachment is checked first, so a stranger learns
     * nothing, not even the text rules. The report is saved in one transaction: if it fails, nothing is kept and the
     * caller gets REPORT_SAVE_FAILED, so the front keeps the draft and offers to try again (32.6).
     */
    public ReadableReport create(UUID practitionerId, UUID patientId, String content) {
        requireAttached(practitionerId, patientId);
        validate(content);
        UUID id = UUID.randomUUID();
        ConsultationReport report = new ConsultationReport(id, patientId, practitionerId, cipher.encrypt(id, patientId, content));
        ConsultationReport saved;
        try {
            saved = transaction.execute(status -> reports.saveAndFlush(report));
        } catch (DataAccessException | TransactionException failed) {
            // The cause stays out of the message: it could describe the database
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT_SAVE_FAILED",
                    "Le compte-rendu n'a pas pu être enregistré. Réessayez dans un instant.");
        }
        return new ReadableReport(saved.getId(), saved.getCreatedAt(), content);
    }

    /** The patient's reports, newest first, whoever of their practitioners wrote them. */
    @Transactional(readOnly = true)
    public List<ReadableReport> list(UUID practitionerId, UUID patientId) {
        requireAttached(practitionerId, patientId);
        return reports.findByPatientIdOrderByCreatedAtDesc(patientId).stream()
                .map(report -> new ReadableReport(report.getId(), report.getCreatedAt(),
                        cipher.decrypt(report.getId(), report.getPatientId(), report.getContentEncrypted())))
                .toList();
    }

    /** Not attached, unknown or malformed: the record's 403 (audited as a refusal by the access-denied handler). */
    private void requireAttached(UUID practitionerId, UUID patientId) {
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
