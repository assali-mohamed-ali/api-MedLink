package com.example.apimedlink.controller;

import com.example.apimedlink.dto.AlertDto;
import com.example.apimedlink.mapper.AlertMapper;
import com.example.apimedlink.security.CurrentUser;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.AlertService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * MLink-37 (37.3): the practitioner's alerts, for the bell of the portal. Always the caller's own: no route takes a
 * practitioner id. {@code SecurityConfig} limits {@code /api/practitioner/**} to practitioners before anything is read.
 */
@RestController
@RequestMapping("/api/practitioner/alerts")
@PreAuthorize("hasRole('" + Roles.PRACTITIONER + "')")
public class PractitionerAlertController {

    private final AlertService service;
    private final CurrentUser currentUser;

    public PractitionerAlertController(AlertService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    /** {@code unread=true} (the default, what the bell asks every minute): the unread ones; {@code false}: all of them. */
    @Operation(summary = "The practitioner's alerts, newest first")
    @GetMapping
    public List<AlertDto> list(@RequestParam(required = false, defaultValue = "true") String unread) {
        boolean unreadOnly = !"false".equalsIgnoreCase(unread);
        return service.list(currentUser.id(), unreadOnly).stream().map(AlertMapper::toDto).toList();
    }

    @Operation(summary = "Mark one of the practitioner's alerts read")
    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable String id) {
        service.markRead(currentUser.id(), alertId(id));
    }

    /** An id that is not a UUID is nobody's alert: the same 403 as another practitioner's, never a 400. */
    private static UUID alertId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException notAnId) {
            throw new AccessDeniedException("Not an alert id");
        }
    }
}
