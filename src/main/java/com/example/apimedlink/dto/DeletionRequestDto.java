package com.example.apimedlink.dto;

import java.time.Instant;

/**
 * US-16: the state of the caller's deletion request. {@code requested} false: no request, everything else null.
 * {@code status} is PENDING, APPROVED or REJECTED; {@code dueAt} is the date by which staff must answer;
 * {@code decisionNote} is the reason staff gave when they refused; {@code decidedAt} when staff decided;
 * {@code accountClosedAt} when an approval closed the account. Neither a request nor its approval deletes data.
 */
public record DeletionRequestDto(boolean requested, String status, Instant requestedAt, Instant dueAt,
        String decisionNote, Instant decidedAt, Instant accountClosedAt) {}
