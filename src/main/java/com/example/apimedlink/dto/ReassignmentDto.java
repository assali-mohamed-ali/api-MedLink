package com.example.apimedlink.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** US-29: no staff actor, medical text or patient identity is returned to either approval party. */
public record ReassignmentDto(UUID id, UUID appointmentId, SlotRef original, SlotRef target,
        String status, boolean patientAccepted, boolean practitionerAccepted) {
    public record SlotRef(UUID id, UUID clinicId, String clinicName, UUID practitionerId,
            String firstName, String lastName, Instant startsAt, Instant endsAt) {}
    public record Candidate(UUID appointmentId, String patientFirstName, String patientLastName, SlotRef original) {}
    public record Page<T>(List<T> items, int page, int size, long total) {}
}
