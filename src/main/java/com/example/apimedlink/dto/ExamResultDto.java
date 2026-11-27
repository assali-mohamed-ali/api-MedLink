package com.example.apimedlink.dto;

import java.time.Instant;
import java.util.UUID;

/** US-13 / US-25: an examination result. {@code practitioner} is null in the practitioner's own answer to a publish. */
public record ExamResultDto(UUID id, Instant createdAt, String title, String content, Author practitioner) {

    public record Author(String firstName, String lastName) {}
}
