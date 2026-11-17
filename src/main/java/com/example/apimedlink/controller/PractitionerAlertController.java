package com.example.apimedlink.controller;

import com.example.apimedlink.dto.AlertDto;
import com.example.apimedlink.mapper.AlertMapper;
import com.example.apimedlink.security.CurrentUser;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.AlertService;
import io.swagger.v3.oas.annotations.Operation;
import com.example.apimedlink.exception.ApiException;
import java.util.List;
import java.util.Map;
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

    /**
     * {@code unread=true} (the default, what the bell asks every minute): the unread ones; {@code false}: all of them.
     * One page at a time (review on #125): {@code page} from 0, {@code size} 20 by default and 50 at most; out of range
     * is a 400 naming the parameter, as on {@code /api/admin/users}.
     */
    @Operation(summary = "The practitioner's alerts, newest first, one page at a time")
    @GetMapping
    public List<AlertDto> list(
            @RequestParam(required = false, defaultValue = "true") String unread,
            @RequestParam(required = false, defaultValue = "0") String page,
            @RequestParam(required = false, defaultValue = "" + AlertService.DEFAULT_PAGE_SIZE) String size) {
        boolean unreadOnly = !"false".equalsIgnoreCase(unread);
        int pageNumber = number("page", page, 0, Integer.MAX_VALUE, "Le numéro de page doit être un nombre positif ou nul.");
        int pageSize = number("size", size, 1, AlertService.MAX_PAGE_SIZE,
                "La taille de page doit être comprise entre 1 et " + AlertService.MAX_PAGE_SIZE + ".");
        return service.list(currentUser.id(), unreadOnly, pageNumber, pageSize).stream().map(AlertMapper::toDto).toList();
    }

    /** A whole number between min and max, or a 400 with the field's message (a word is out of range too). */
    private static int number(String field, String text, int min, int max, String message) {
        try {
            int value = Integer.parseInt(text.trim());
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException notANumber) {
            // the same answer as a number out of range
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains paramètres sont invalides.",
                Map.of(field, message));
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
