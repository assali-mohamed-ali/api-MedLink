package com.example.apimedlink.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * US-21: an absence of the practitioner. {@code closedFreeSlots} and {@code toDecide} are only filled when the absence has
 * just been declared (null otherwise): the booked appointments it covers, waiting for a decision.
 */
public record AbsenceDto(UUID id, Instant startsAt, Instant endsAt, String reason, Integer closedFreeSlots,
        List<ToDecide> toDecide) {

    /** A booked appointment the absence covers: where and when, and the patient's names (nothing medical). */
    public record ToDecide(UUID appointmentId, Instant startsAt, Instant endsAt, String clinic, String patientFirstName,
            String patientLastName) {}
}
