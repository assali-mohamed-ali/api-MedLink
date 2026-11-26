package com.example.apimedlink.dto;

import java.time.Instant;
import java.util.UUID;

/** A consultation report as its patient reads it (MLink-32 follow-up): the text, and who wrote it. */
public record MyReportDto(UUID id, Instant createdAt, String content, Author practitioner) {

    public record Author(String firstName, String lastName, String specialty) {}
}
