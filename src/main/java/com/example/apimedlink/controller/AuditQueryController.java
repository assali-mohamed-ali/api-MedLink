package com.example.apimedlink.controller;

import com.example.apimedlink.dto.AuditEntryDto;
import com.example.apimedlink.dto.AuditPageDto;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.mapper.AuditEntryMapper;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.AuditQueryService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** MLink-115: administrative, paginated reading of the audit trail. */
@RestController
@RequestMapping("/api/admin/audit-entries")
public class AuditQueryController {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;

    private final AuditQueryService queries;

    public AuditQueryController(AuditQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    @PreAuthorize("hasRole('" + Roles.ADMINISTRATIVE_STAFF + "')")
    public AuditPageDto search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw invalid("to", "La date de fin doit être égale ou postérieure à la date de début.");
        }
        if (page < 0) {
            throw invalid("page", "Le numéro de page doit être positif ou nul.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw invalid("size", "La taille de page doit être comprise entre 1 et 100.");
        }

        AuditAction filter = parseAction(action);
        Page<AuditEntryDto> results = queries.search(from, to, userId, filter, page, size)
                .map(AuditEntryMapper::toDto);
        return new AuditPageDto(
                results.getContent(),
                results.getNumber(),
                results.getSize(),
                results.getTotalElements(),
                results.getTotalPages());
    }

    private static AuditAction parseAction(String action) {
        if (action == null) {
            return null;
        }
        try {
            return AuditAction.valueOf(action);
        } catch (IllegalArgumentException invalidAction) {
            throw invalid("action", "Le type d'action demandé n'existe pas.");
        }
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains paramètres sont invalides.",
                Map.of(field, message));
    }
}
