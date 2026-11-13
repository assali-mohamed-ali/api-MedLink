package com.example.apimedlink.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A practitioner opens a slot (MLink-26). No bean validation on purpose: Spring would check it before
 * {@code @PreAuthorize}, so a patient would get 400 instead of 403; the controller checks the fields itself.
 */
public record OpenSlotRequest(UUID clinicId, Instant startsAt, Instant endsAt) {}
