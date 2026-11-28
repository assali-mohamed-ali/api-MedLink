package com.example.apimedlink.service;

import com.example.apimedlink.model.DeletionRequest;
import com.example.apimedlink.model.Patient;
import java.time.Instant;

/**
 * US-16: a deletion request as staff review it: who asks, the date by which to answer, the latest consultation and
 * the end of the retention period it starts (null when the patient never had a consultation).
 */
public record DeletionReview(DeletionRequest request, Patient patient, Instant dueAt, Instant lastConsultationAt,
        Instant retainedUntil) {}
