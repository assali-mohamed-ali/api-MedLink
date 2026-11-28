package com.example.apimedlink.service;

import java.time.Instant;
import java.util.UUID;

/** A consultation report as its patient reads it (MLink-32 follow-up): the text opened, and who wrote it. */
public record PatientReport(UUID id, Instant createdAt, String content, String practitionerFirstName,
        String practitionerLastName, String specialty) {}
