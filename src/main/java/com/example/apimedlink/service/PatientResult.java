package com.example.apimedlink.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * US-13 / US-25: an examination result opened for its reader. {@code isNew}: the patient had not seen it before (false
 * for the practitioner's view). Practitioner names are null in the practitioner's own view.
 */
public record PatientResult(UUID id, Instant publishedAt, LocalDate examDate, String title, String examType,
        String content, UUID documentId, boolean isNew, String practitionerFirstName, String practitionerLastName) {}
