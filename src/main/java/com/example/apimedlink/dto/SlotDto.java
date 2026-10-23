package com.example.apimedlink.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A free slot as a patient sees it in the search (MLink-98). Of the practitioner, only the names:
 * never an email or a username.
 */
public record SlotDto(
        UUID slotId,
        Instant startsAt,
        Instant endsAt,
        ClinicRef clinic,
        PractitionerRef practitioner,
        String specialty) {

    public record ClinicRef(UUID id, String name) {}

    public record PractitionerRef(UUID id, String firstName, String lastName) {}
}
