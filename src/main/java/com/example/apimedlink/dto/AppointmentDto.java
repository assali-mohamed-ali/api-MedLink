package com.example.apimedlink.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * An appointment as its patient sees it (MLink-102): where, with whom and when. Of the practitioner, only the
 * names: never an email or a username.
 */
public record AppointmentDto(
        UUID id,
        String status,
        SlotDto.ClinicRef clinic,
        SlotDto.PractitionerRef practitioner,
        Instant startsAt,
        Instant endsAt) {}
