package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.RegisterRequest;
import com.example.apimedlink.model.Patient;
import java.time.Instant;

/** API shape to entity. Called by controllers: services never see a DTO. */
public final class PatientMapper {

    private PatientMapper() {}

    /** The profile part of a sign-up; the registration service sets the account's id and email. */
    public static Patient toNewPatient(RegisterRequest request) {
        Patient patient = new Patient(
                request.firstName().trim(),
                request.lastName().trim(),
                request.birthDate(),
                blankToNull(request.phone()),
                blankToNull(request.address()));
        // US-04: the consent the visitor gave (already checked against the current text by ConsentPolicy)
        String consent = blankToNull(request.consentVersion());
        if (consent != null) {
            patient.setConsentVersion(consent);
            patient.setConsentedAt(Instant.now());
        }
        return patient;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
