package com.example.apimedlink.dto;

import java.time.Instant;

/**
 * US-16: the state of the caller's deletion request. {@code requested} false: no request, both dates null.
 * {@code scheduledFor} is the end of the grace period; until the purge exists, nothing is deleted at that date.
 */
public record DeletionRequestDto(boolean requested, Instant requestedAt, Instant scheduledFor) {}
