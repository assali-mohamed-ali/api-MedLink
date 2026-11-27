package com.example.apimedlink.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * US-16: a deletion request as staff review it. Names only (nothing medical): when the patient asked, the date by which
 * to answer, the latest consultation and the end of the retention period it starts ({@code retainedUntil} null: none).
 */
public record DeletionReviewDto(UUID patientId, String firstName, String lastName, String status, Instant requestedAt,
        Instant dueAt, Instant lastConsultationAt, Instant retainedUntil, String decisionNote) {}
