package com.example.apimedlink.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** US-30: staff book a slot for a patient (for instance a patient who called the clinic). */
public record StaffBookingRequest(
        @NotNull(message = "Le patient est obligatoire.") UUID patientId,
        @NotNull(message = "Le créneau est obligatoire.") UUID slotId) {}
