package com.example.apimedlink.dto;

import java.util.List;
import java.util.UUID;

/**
 * GET /api/admin/profile-choices (MLink-69): the clinics and specialties a practitioner's profile is chosen from,
 * in French order by name. Unlike the slot search lists, no practitioner.
 */
public record ProfileChoicesDto(List<ClinicItem> clinics, List<SpecialtyItem> specialties) {

    public record ClinicItem(UUID id, String name, String city) {}

    public record SpecialtyItem(UUID id, String name) {}
}
