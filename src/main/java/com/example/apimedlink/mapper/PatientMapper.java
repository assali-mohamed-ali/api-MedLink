package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.RegisterRequest;
import com.example.apimedlink.model.Patient;

/** API shape to entity. Called by controllers: services never see a DTO. */
public final class PatientMapper {

    private PatientMapper() {}

    /** The profile part of a sign-up; the registration service sets the account's id and email. */
    public static Patient toNewPatient(RegisterRequest request) {
        return new Patient(
                request.firstName().trim(),
                request.lastName().trim(),
                request.birthDate(),
                blankToNull(request.phone()),
                blankToNull(request.address()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
