package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.RegisterRequest;
import com.example.apimedlink.model.Patient;

/** API shape to entity. Called by controllers: services never see a DTO. */
public final class PatientMapper {

    private PatientMapper() {}

    /** The profile part of a signup; its user is attached by the registration service. */
    public static Patient toNewPatient(RegisterRequest request) {
        return Patient.builder()
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .birthDate(request.birthDate())
                .phone(blankToNull(request.phone()))
                .address(blankToNull(request.address()))
                .build();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
