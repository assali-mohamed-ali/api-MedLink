package com.example.apimedlink.dto;

import java.time.Instant;
import java.util.UUID;

/** US-21: what happened to an appointment. {@code newAppointmentId} and {@code newStartsAt} are null for a cancel. */
public record AbsenceDecisionDto(String action, UUID appointmentId, UUID newAppointmentId, Instant newStartsAt) {}
