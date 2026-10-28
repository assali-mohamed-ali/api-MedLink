package com.example.apimedlink.dto;

import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import java.time.Instant;
import java.util.UUID;

/** A safe, read-only view of an audit entry. */
public record AuditEntryDto(
        UUID id,
        Instant timestamp,
        UUID userId,
        AuditAction action,
        String resourceType,
        String resourceId,
        String ipAddress,
        AuditOutcome outcome) {}
