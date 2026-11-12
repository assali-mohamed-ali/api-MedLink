package com.example.apimedlink.service;

import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.Patient;
import com.example.apimedlink.repository.AppointmentRepository;
import com.example.apimedlink.repository.PatientRepository;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public PatientRecordService(AppointmentRepository appointments, PatientRepository patients, AuditService audit) {
        this.appointments = appointments;
        this.patients = patients;
        this.audit = audit;
    }

    /**
     * The record, if the patient is attached to this practitioner. The link is checked before anything is read, so a
     * patient who is not attached and one who does not exist get the same {@link AccessDeniedException} (403, audited
     * as a refusal by the access-denied handler).
     */
    @Transactional(readOnly = true)
    public PatientRecord read(UUID practitionerId, UUID patientId) {
        if (!appointments.existsByPractitionerAndPatient(practitionerId, patientId)) {
            throw notAttached();
        }
        Patient patient = patients.findById(patientId).orElseThrow(PatientRecordService::notAttached);
        // Written before the record leaves: a read that cannot be traced is not answered (in its own transaction)
        audit.record(AuditAction.RECORD_VIEWED, RESOURCE, patientId.toString(), AuditOutcome.SUCCESS);
        return new PatientRecord(patient);
    }

    private static AccessDeniedException notAttached() {
        return new AccessDeniedException("Patient not attached to the practitioner");
    }
}
