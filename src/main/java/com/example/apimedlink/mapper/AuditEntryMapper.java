package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.AuditEntryDto;
import com.example.apimedlink.model.AuditEntry;

/** Maps audit entities to the API's read-only response shape. */
public final class AuditEntryMapper {

    private AuditEntryMapper() {}

    public static AuditEntryDto toDto(AuditEntry entry) {
        return new AuditEntryDto(
                entry.getId(),
                entry.getTimestamp(),
                entry.getUserId(),
                entry.getAction(),
                entry.getResourceType(),
                entry.getResourceId(),
                entry.getIpAddress(),
                entry.getOutcome());
    }
}
