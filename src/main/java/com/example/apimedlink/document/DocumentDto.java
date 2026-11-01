package com.example.apimedlink.document;

import java.time.Instant;
import java.util.UUID;

/** The metadata of a document as React sees it; never a byte of the document. */
public record DocumentDto(
        UUID id,
        UUID patientId,
        UUID uploadedBy,
        String title,
        String originalFilename,
        String contentType,
        long sizeBytes,
        Instant createdAt) {}
