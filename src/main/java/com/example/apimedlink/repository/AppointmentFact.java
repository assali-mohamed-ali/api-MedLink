package com.example.apimedlink.repository;

import com.example.apimedlink.model.AppointmentStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * What a statistic may know about one appointment (MLink-95): when its slot starts, its status, the specialty, and
 * the patient's id. The patient id stays inside the service, only to count distinct people per group (a group of
 * five appointments of one patient is one person, not five). It is never written to the export or to the audit
 * trail. No name and no contact detail is ever read.
 */
public record AppointmentFact(Instant startsAt, AppointmentStatus status, String specialty, UUID patientId) {
}
