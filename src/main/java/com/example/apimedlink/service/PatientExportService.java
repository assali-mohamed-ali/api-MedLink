package com.example.apimedlink.service;

import com.example.apimedlink.document.PatientDocument;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.Appointment;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.DeletionRequest;
import com.example.apimedlink.model.Patient;
import com.example.apimedlink.repository.AppointmentRepository;
import com.example.apimedlink.repository.DeletionRequestRepository;
import com.example.apimedlink.repository.PatientDocumentRepository;
import com.example.apimedlink.repository.PatientRepository;
import com.example.apimedlink.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * US-16: a patient exports their own data: profile (with the consent version and time recorded at sign-up and the
 * notification preferences), appointments, consultation reports and examination results (texts opened), the list of
 * their documents (the files themselves, attachments of results included, are downloaded one by one from their
 * authenticated route, as today) and the state of their deletion request. The patient is always the caller. The read
 * is traced (DATA_READ, PATIENT_EXPORT) once it is done; the reports' and results' own reads are traced as well. The
 * export never marks a result as seen. Takes and returns entities and plain values, never a DTO. Never logs what it
 * reads.
 */
@Service
public class PatientExportService {

    static final String RESOURCE = "PATIENT_EXPORT";

    private final PatientRepository patients;
    private final UserRepository users;
    private final AppointmentRepository appointments;
    private final PatientDocumentRepository documents;
    private final DeletionRequestRepository deletionRequests;
    private final PatientDeletionService deletion;
    private final ConsultationReportService reports;
    private final ExamResultService results;
    private final AuditService audit;
    private final TransactionTemplate readOnly;

    public PatientExportService(PatientRepository patients, UserRepository users, AppointmentRepository appointments,
            PatientDocumentRepository documents, DeletionRequestRepository deletionRequests,
            PatientDeletionService deletion,
            ConsultationReportService reports, ExamResultService results, AuditService audit,
            PlatformTransactionManager transactionManager) {
        this.patients = patients;
        this.users = users;
        this.appointments = appointments;
        this.documents = documents;
        this.deletionRequests = deletionRequests;
        this.deletion = deletion;
        this.reports = reports;
        this.results = results;
        this.audit = audit;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    public PatientExport export(UUID patientId) {
        PatientExport export = readOnly.execute(status -> {
            Patient patient = patients.findById(patientId).orElseThrow(() ->
                    new ApiException(HttpStatus.NOT_FOUND, "PATIENT_NOT_FOUND", "Ce profil patient n'existe pas."));
            String email = users.findById(patientId).map(user -> user.getEmail()).orElse(null);
            List<Appointment> mine = appointments.findByPatientId(patientId,
                    Pageable.unpaged(Sort.by(Sort.Order.desc("slot.startsAt"), Sort.Order.desc("id")))).getContent();
            List<PatientDocument> files = documents.findByPatientId(patientId,
                    Pageable.unpaged(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))).getContent();
            Optional<DeletionRequest> request = deletionRequests.findById(patientId);
            return new PatientExport(patient, email, mine, List.of(), List.of(), files, request,
                    request.map(deletion::dueAt).orElse(null));
        });
        // Reports and results are opened (and their read traced) by their own services, after the read transaction
        List<PatientReport> written = reports.mine(patientId);
        List<PatientResult> published = results.forExport(patientId);
        audit.record(AuditAction.DATA_READ, RESOURCE, patientId.toString(), AuditOutcome.SUCCESS);
        return new PatientExport(export.patient(), export.email(), export.appointments(), written, published,
                export.documents(), export.deletionRequest(), export.deletionDueAt());
    }
}
