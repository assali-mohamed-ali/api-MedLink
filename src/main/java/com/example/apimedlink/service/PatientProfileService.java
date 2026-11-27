package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.Patient;
import com.example.apimedlink.repository.PatientRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-15: a patient reads and updates their own contact details (phone, address). The id is always the caller's,
 * never taken from a request. The change and its audit entry are kept or lost together. Never logs the values.
 * Takes and returns entities and plain values, never a DTO.
 */
@Service
public class PatientProfileService {

    static final String RESOURCE = "PATIENT";
    static final String RESOURCE_PREFERENCES = "PATIENT_NOTIFICATIONS";

    private final PatientRepository patients;
    private final AuditService audit;

    public PatientProfileService(PatientRepository patients, AuditService audit) {
        this.patients = patients;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Patient get(UUID patientId) {
        return patients.findById(patientId).orElseThrow(PatientProfileService::notFound);
    }

    /** Sets phone and address (null clears them) and records the change in the same transaction. */
    @Transactional
    public Patient update(UUID patientId, String phone, String address) {
        Patient patient = patients.findById(patientId).orElseThrow(PatientProfileService::notFound);
        patient.setPhone(phone);
        patient.setAddress(address);
        Patient saved = patients.save(patient);
        audit.recordWithin(AuditAction.ACCOUNT_UPDATED, RESOURCE, patientId.toString(), AuditOutcome.SUCCESS);
        return saved;
    }

    /** US-15: replaces both notification preferences, audited in the same transaction (no value in the entry). */
    @Transactional
    public Patient updatePreferences(UUID patientId, boolean appointmentReminders, boolean examResults) {
        Patient patient = patients.findById(patientId).orElseThrow(PatientProfileService::notFound);
        patient.setNotifyAppointmentReminders(appointmentReminders);
        patient.setNotifyExamResults(examResults);
        Patient saved = patients.save(patient);
        audit.recordWithin(AuditAction.ACCOUNT_UPDATED, RESOURCE_PREFERENCES, patientId.toString(), AuditOutcome.SUCCESS);
        return saved;
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "PATIENT_NOT_FOUND", "Ce profil patient n'existe pas.");
    }
}
