package com.example.apimedlink.dto;

import com.example.apimedlink.model.AlertType;
import java.time.Instant;
import java.util.UUID;

/** An alert as the bell of the practitioner shows it (MLink-37, the front's contract of 37.5). */
public record AlertDto(UUID id, AlertType type, String message, UUID appointmentId, Instant createdAt) {}
