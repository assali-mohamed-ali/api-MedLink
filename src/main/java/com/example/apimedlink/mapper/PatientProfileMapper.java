package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.PatientProfileDto;
import com.example.apimedlink.model.Patient;

/** US-15: entity to API shape, and the cleaning of a submitted value. Called by controllers only. */
public final class PatientProfileMapper {

    private PatientProfileMapper() {}

    public static PatientProfileDto toDto(Patient patient) {
        return new PatientProfileDto(patient.getPhone(), patient.getAddress());
    }

    /** A blank value means "clear it"; anything else is trimmed. */
    public static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
