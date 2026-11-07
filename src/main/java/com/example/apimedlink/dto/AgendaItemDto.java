package com.example.apimedlink.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * One slot of a practitioner's agenda (MLink-26). {@code status} is {@code OPEN}, {@code BOOKED} or
 * {@code CLOSED}. Of the patient who holds the slot, only the id (to open their record) and the names.
 */
public record AgendaItemDto(
        UUID slotId,
        String status,
        Instant startsAt,
        Instant endsAt,
        SlotDto.ClinicRef clinic,
        @JsonInclude(JsonInclude.Include.NON_NULL) AppointmentRef appointment) {

    public record AppointmentRef(UUID id, String status, PatientRef patient) {}

    public record PatientRef(UUID id, String firstName, String lastName) {}
}
