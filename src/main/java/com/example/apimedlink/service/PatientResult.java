package com.example.apimedlink.service;

import java.time.Instant;
import java.util.UUID;

/** US-13: an examination result as its patient reads it: opened, with who published it. */
public record PatientResult(UUID id, Instant createdAt, String title, String content, String practitionerFirstName,
        String practitionerLastName) {}
