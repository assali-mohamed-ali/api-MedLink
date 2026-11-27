package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.ExamResult;
import com.example.apimedlink.model.Practitioner;
import com.example.apimedlink.repository.AppointmentRepository;
import com.example.apimedlink.repository.ExamResultRepository;
import com.example.apimedlink.repository.PractitionerRepository;
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
 * US-25: a practitioner publishes an examination result for a patient attached to them (a BOOKED appointment, the
 * record's rule MLink-31); US-13: the patient reads their own results. Title and content are sealed with the reports'
 * cipher before they reach the database and opened only here. Writes and reads are audited (ids only). Never logs a
 * title or a content. Takes and returns entities and plain values, never a DTO.
 */
@Service
public class ExamResultService {

    static final int MAX_TITLE = 200;
    static final int MAX_CONTENT = 10_000;
    static final String RESOURCE_RESULT = "EXAM_RESULT";
    static final String RESOURCE_PATIENT_RESULTS = "PATIENT_RESULTS";

    private final ExamResultRepository results;
    private final AppointmentRepository appointments;
    private final PractitionerRepository practitioners;
    private final ReportCipher cipher;
    private final AuditService audit;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;

    public ExamResultService(ExamResultRepository results, AppointmentRepository appointments,
            PractitionerRepository practitioners, ReportCipher cipher, AuditService audit,
            PlatformTransactionManager transactionManager) {
        this.results = results;
        this.appointments = appointments;
        this.practitioners = practitioners;
        this.cipher = cipher;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /** 403 when the patient is not attached to the practitioner (or does not exist); 400 for an empty or too long text. */
    public ExamResult publish(UUID practitionerId, UUID patientId, String title, String content) {
        String cleanTitle = title == null ? "" : title.trim();
        String cleanContent = content == null ? "" : content.trim();
        if (cleanTitle.isEmpty() || cleanTitle.length() > MAX_TITLE || cleanContent.isEmpty()
                || cleanContent.length() > MAX_CONTENT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains champs sont invalides.",
                    Map.of("content", "Le titre (200 caractères au plus) et le résultat (10 000 au plus) sont obligatoires."));
        }
        return transaction.execute(status -> {
            if (!appointments.existsByPractitionerAndPatient(practitionerId, patientId)) {
                throw new AccessDeniedException("Patient not attached to the practitioner");
            }
            UUID id = UUID.randomUUID();
            ExamResult saved = results.save(new ExamResult(id, patientId, practitionerId,
                    cipher.encrypt(id, patientId, cleanTitle), cipher.encrypt(id, patientId, cleanContent)));
            audit.recordWithin(AuditAction.REPORT_CREATED, RESOURCE_RESULT, id.toString(), AuditOutcome.SUCCESS);
            return saved;
        });
    }

    /** The patient's own results, newest first, opened, with who published each one. The read is traced after it. */
    public List<PatientResult> mine(UUID patientId) {
        List<PatientResult> found = readOnly.execute(status -> {
            List<ExamResult> published = results.findByPatientIdOrderByCreatedAtDesc(patientId);
            Map<UUID, Practitioner> authors = practitioners
                    .findAllById(published.stream().map(ExamResult::getPractitionerId).distinct().toList())
                    .stream().collect(Collectors.toMap(Practitioner::getId, Function.identity()));
            return published.stream().map(result -> {
                Practitioner author = authors.get(result.getPractitionerId());
                return new PatientResult(result.getId(), result.getCreatedAt(),
                        cipher.decrypt(result.getId(), patientId, result.getTitleEncrypted()),
                        cipher.decrypt(result.getId(), patientId, result.getContentEncrypted()),
                        author == null ? null : author.getFirstName(), author == null ? null : author.getLastName());
            }).toList();
        });
        audit.record(AuditAction.REPORT_VIEWED, RESOURCE_PATIENT_RESULTS, patientId.toString(), AuditOutcome.SUCCESS);
        return found;
    }
}
