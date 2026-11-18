package com.example.apimedlink.repository;

import com.example.apimedlink.model.AppointmentStatus;
import java.time.Instant;

/**
 * What a statistic may know about one appointment (MLink-95): when its slot starts, its status and the specialty.
 * Deliberately no patient, no practitioner and no contact detail, so none can reach an export.
 */
public record AppointmentFact(Instant startsAt, AppointmentStatus status, String specialty) {
}
