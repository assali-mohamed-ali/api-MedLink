package com.example.apimedlink.service;

import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.Patient;
import com.example.apimedlink.repository.AppointmentRepository;
import com.example.apimedlink.repository.PatientRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A patient's record, for the practitioners the patient is attached to (MLink-31): a practitioner who has a
 * {@code BOOKED} appointment with them, past or upcoming. Takes and returns entities, never a DTO.
 */
@Service
public class PatientRecordService {

    static final String RESOURCE = "PATIENT_RECORD";

    private final AppointmentRepository appointments;
    private final PatientRepository patients;
    private final AuditService audit;
    private final TransactionTemplate readOnly;

    public PatientRecordService(AppointmentRepository appointments, PatientRepository patients, AuditService audit,
            PlatformTransactionManager transactionManager) {
        this.appointments = appointments;
        this.patients = patients;
        this.audit = audit;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /**
     * The record, if the patient is attached to this practitioner. The link is checked before anything is read, so a
     * patient who is not attached and one who does not exist get the same {@link AccessDeniedException} (403, audited
     * as a refusal by the access-denied handler). The read transaction ends and gives its connection back before the
     * RECORD_VIEWED row is written in its own (review on #123): a read never holds two connections.
     */
    public PatientRecord read(UUID practitionerId, UUID patientId) {
        PatientRecord record = readOnly.execute(status -> {
            if (!appointments.existsByPractitionerAndPatient(practitionerId, patientId)) {
                throw notAttached();
            }
            Patient patient = patients.findById(patientId).orElseThrow(PatientRecordService::notAttached);
            return new PatientRecord(patient,
                    appointments.existsStartedByPractitionerAndPatient(practitionerId, patientId, Instant.now()));
        });
        // Written before the record leaves: a read that cannot be traced is not answered
        audit.record(AuditAction.RECORD_VIEWED, RESOURCE, patientId.toString(), AuditOutcome.SUCCESS);
        return record;
    }

    private static AccessDeniedException notAttached() {
        return new AccessDeniedException("Patient not attached to the practitioner");
    }
}
