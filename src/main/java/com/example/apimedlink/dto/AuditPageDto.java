package com.example.apimedlink.dto;

import java.util.List;

/** Stable pagination response for an audit-log query. */
public record AuditPageDto(
        List<AuditEntryDto> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {}
