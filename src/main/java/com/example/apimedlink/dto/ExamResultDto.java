package com.example.apimedlink.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * US-13 / US-25: an examination result. {@code publishedAt} is when it was published, {@code examDate} the day of the
 * examination. {@code documentId}: the attached document of the patient, downloaded from its own route. {@code isNew}:
 * the patient had not seen it before this list (always false for the practitioner). {@code practitioner} is null in the
 * practitioner's own lists.
 */
public record ExamResultDto(UUID id, Instant publishedAt, LocalDate examDate, String title, String examType,
        String content, UUID documentId, boolean isNew, Author practitioner) {

    public record Author(String firstName, String lastName) {}
}
