package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.ExamResult;
import com.example.apimedlink.model.Practitioner;
import com.example.apimedlink.repository.AppointmentRepository;
import com.example.apimedlink.repository.ExamResultRepository;
import com.example.apimedlink.repository.PatientDocumentRepository;
import com.example.apimedlink.repository.PractitionerRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * US-25: a practitioner publishes an examination result (title, examination type, date, content, optionally one of the
 * patient's existing documents) for a patient attached to them (a BOOKED appointment, the record's rule MLink-31), and
 * lists what they published for that patient. US-13: the patient reads their own results, newest first, with who
 * published them and which ones are new; reading marks them seen. Title, type and content are sealed with the reports'
 * cipher, each with its own associated data (a sealed field cannot be swapped for another). A result is published at
 * once and never changed: there is no draft state the patient could see. Writes and reads are audited (ids only). Never
 * logs a medical text. Takes and returns entities and plain values, never a DTO.
 */
@Service
public class ExamResultService {

    static final int MAX_TITLE = 200;
    static final int MAX_TYPE = 100;
    static final int MAX_CONTENT = 10_000;
    static final String RESOURCE_RESULT = "EXAM_RESULT";
    static final String RESOURCE_PATIENT_RESULTS = "PATIENT_RESULTS";
    static final ZoneId CLINICS_ZONE = ZoneId.of("Africa/Tunis");

    private final ExamResultRepository results;
    private final AppointmentRepository appointments;
    private final PractitionerRepository practitioners;
    private final PatientDocumentRepository documents;
    private final ReportCipher cipher;
    private final AuditService audit;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;

    public ExamResultService(ExamResultRepository results, AppointmentRepository appointments,
            PractitionerRepository practitioners, PatientDocumentRepository documents, ReportCipher cipher,
            AuditService audit, PlatformTransactionManager transactionManager) {
        this.results = results;
        this.appointments = appointments;
        this.practitioners = practitioners;
        this.documents = documents;
        this.cipher = cipher;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /**
     * 403 when the patient is not attached to the practitioner (or does not exist); 400 naming each invalid field (empty
     * or too long text, a missing or future date, a document that is not the patient's).
     */
    public ExamResult publish(UUID practitionerId, UUID patientId, String title, String examType, LocalDate examDate,
            String content, UUID documentId) {
        String cleanTitle = title == null ? "" : title.trim();
        String cleanType = examType == null ? "" : examType.trim();
        String cleanContent = content == null ? "" : content.trim();
        Map<String, String> errors = new LinkedHashMap<>();
        if (cleanTitle.isEmpty() || cleanTitle.length() > MAX_TITLE) {
            errors.put("title", "Le titre est obligatoire (200 caractères au plus).");
        }
        if (cleanType.isEmpty() || cleanType.length() > MAX_TYPE) {
            errors.put("examType", "Le type d'examen est obligatoire (100 caractères au plus).");
        }
        if (cleanContent.isEmpty() || cleanContent.length() > MAX_CONTENT) {
            errors.put("content", "Le résultat est obligatoire (10 000 caractères au plus).");
        }
        if (examDate == null || examDate.isAfter(LocalDate.now(CLINICS_ZONE))) {
            errors.put("examDate", "La date de l'examen est obligatoire et ne peut pas être dans le futur.");
        }
        return transaction.execute(status -> {
            // The link first: a practitioner who is not attached learns nothing, not even which fields are wrong
            requireAttached(practitionerId, patientId);
            if (documentId != null && documents.findByIdAndPatientId(documentId, patientId).isEmpty()) {
                errors.put("documentId", "Ce document n'appartient pas à ce patient.");
            }
            if (!errors.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains champs sont invalides.",
                        errors);
            }
            UUID id = UUID.randomUUID();
            ExamResult saved = results.save(new ExamResult(id, patientId, practitionerId,
                    cipher.encrypt(fieldId(id, "title"), patientId, cleanTitle),
                    cipher.encrypt(fieldId(id, "type"), patientId, cleanType),
                    cipher.encrypt(id, patientId, cleanContent), examDate, documentId));
            audit.recordWithin(AuditAction.REPORT_CREATED, RESOURCE_RESULT, id.toString(), AuditOutcome.SUCCESS);
            return saved;
        });
    }

    /** The patient's own results, newest first, opened; the unseen ones are marked new, then seen. Traced after. */
    public List<PatientResult> mine(UUID patientId) {
        List<PatientResult> found = readOnly.execute(status -> openOwn(patientId));
        transaction.executeWithoutResult(status -> results.markRead(patientId, Instant.now()));
        audit.record(AuditAction.REPORT_VIEWED, RESOURCE_PATIENT_RESULTS, patientId.toString(), AuditOutcome.SUCCESS);
        return found;
    }

    /**
     * US-16: the patient's own results for their export, newest first, opened. Unlike {@link #mine} it does not mark
     * them seen: downloading an archive is not reading the results in the space, and the "new" marker stays. Traced.
     */
    public List<PatientResult> forExport(UUID patientId) {
        List<PatientResult> found = readOnly.execute(status -> openOwn(patientId));
        audit.record(AuditAction.REPORT_VIEWED, RESOURCE_PATIENT_RESULTS, patientId.toString(), AuditOutcome.SUCCESS);
        return found;
    }

    /** What this practitioner published for an attached patient, newest first. 403 when not attached. Traced after. */
    public List<PatientResult> publishedBy(UUID practitionerId, UUID patientId) {
        List<PatientResult> found = readOnly.execute(status -> {
            requireAttached(practitionerId, patientId);
            return results.findByPractitionerIdAndPatientIdOrderByCreatedAtDesc(practitionerId, patientId).stream()
                    .map(result -> open(result, false, null, null)).toList();
        });
        audit.record(AuditAction.REPORT_VIEWED, RESOURCE_PATIENT_RESULTS, patientId.toString(), AuditOutcome.SUCCESS);
        return found;
    }

    /** The patient's results, newest first, opened with their authors; the unseen ones are marked new. */
    private List<PatientResult> openOwn(UUID patientId) {
        List<ExamResult> published = results.findByPatientIdOrderByCreatedAtDesc(patientId);
        Map<UUID, Practitioner> authors = practitioners
                .findAllById(published.stream().map(ExamResult::getPractitionerId).distinct().toList())
                .stream().collect(Collectors.toMap(Practitioner::getId, Function.identity()));
        return published.stream().map(result -> {
            Practitioner author = authors.get(result.getPractitionerId());
            return open(result, result.getReadAt() == null,
                    author == null ? null : author.getFirstName(), author == null ? null : author.getLastName());
        }).toList();
    }

    private PatientResult open(ExamResult result, boolean isNew, String firstName, String lastName) {
        UUID id = result.getId();
        UUID patientId = result.getPatientId();
        return new PatientResult(id, result.getCreatedAt(), result.getExamDate(),
                cipher.decrypt(fieldId(id, "title"), patientId, result.getTitleEncrypted()),
                cipher.decrypt(fieldId(id, "type"), patientId, result.getTypeEncrypted()),
                cipher.decrypt(id, patientId, result.getContentEncrypted()),
                result.getDocumentId(), isNew, firstName, lastName);
    }

    private void requireAttached(UUID practitionerId, UUID patientId) {
        if (!appointments.existsByPractitionerAndPatient(practitionerId, patientId)) {
            throw new AccessDeniedException("Patient not attached to the practitioner");
        }
    }

    /** A distinct, stable id per sealed field of a result, used as the cipher's associated data. */
    private static UUID fieldId(UUID resultId, String field) {
        return UUID.nameUUIDFromBytes((resultId + ":" + field).getBytes(StandardCharsets.UTF_8));
    }
}
