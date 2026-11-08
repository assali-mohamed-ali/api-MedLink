package com.example.apimedlink.dto;

import java.util.List;
import java.util.UUID;

/**
 * The lists of the slot search form (MLink-163), in French order by name. Of a practitioner, only what the form
 * needs: names, specialty and the clinics they work in (never an email or a username).
 */
public record SlotFiltersResponse(
        List<ClinicItem> clinics,
        List<SpecialtyItem> specialties,
        List<PractitionerItem> practitioners) {

    public record ClinicItem(UUID id, String name, String city) {}

    public record SpecialtyItem(UUID id, String name) {}

    public record PractitionerItem(UUID id, String firstName, String lastName, UUID specialtyId, List<UUID> clinicIds) {}
}
