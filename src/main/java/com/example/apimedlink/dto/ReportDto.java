package com.example.apimedlink.dto;

import java.time.Instant;
import java.util.UUID;

/** A consultation report as the practitioner reads it (MLink-32): the text decrypted by the API. */
public record ReportDto(UUID id, Instant createdAt, String content) {}
