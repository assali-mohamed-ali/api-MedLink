package com.example.apimedlink.dto;

import java.util.List;
import java.util.UUID;

/**
 * One practitioner as staff see them to supervise the clinics (MLink-35): names, specialty and the clinics they work
 * in, never an email or a username.
 */
public record AdminPractitionerDto(UUID id, String firstName, String lastName, SpecialtyRef specialty,
                                   List<SlotDto.ClinicRef> clinics) {

    public record SpecialtyRef(UUID id, String name) {}
}
