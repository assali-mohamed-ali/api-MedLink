package com.example.apimedlink.controller;

import com.example.apimedlink.dto.AgendaItemDto;
import com.example.apimedlink.dto.AgendaResponse;
import com.example.apimedlink.dto.OpenSlotRequest;
import com.example.apimedlink.dto.SlotStatusRequest;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.exception.ErrorBody;
import com.example.apimedlink.mapper.AgendaMapper;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.Slot;
import com.example.apimedlink.model.SlotStatus;
import com.example.apimedlink.security.CurrentUser;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.AgendaService;
import com.example.apimedlink.service.AuditService;
import com.example.apimedlink.service.SlotChange;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * The practitioner's agenda across the three clinics, and the opening and closing of their slots (MLink-26).
 * The whole class is for practitioners, so a route added later is protected too ({@code SecurityConfig} only
 * says "logged in"). The practitioner is always the caller: no route takes a practitioner id.
 *
 * <p>Spring checks a required parameter, a path variable's type and a {@code @Valid} body before
 * {@code @PreAuthorize}: a patient would get 400 instead of 403 and learn our validation rules. So nothing
 * here is required or validated by Spring; every rule is checked by hand, after the role check.
 */
@RestController
@RequestMapping("/api/practitioner")
@PreAuthorize("hasRole('" + Roles.PRACTITIONER + "')")
public class PractitionerAgendaController {

    private static final String RESOURCE_SLOT = "SLOT";

    private final AgendaService service;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public PractitionerAgendaController(AgendaService service, AuditService audit, CurrentUser currentUser) {
        this.service = service;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    /** The caller's slots and appointments in the three clinics, with the clinics they work in. */
    @GetMapping("/agenda")
    public AgendaResponse agenda(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (from == null) {
            errors.put("from", "La date de début est obligatoire.");
        }
        if (to == null) {
            errors.put("to", "La date de fin est obligatoire.");
        } else if (from != null && !to.isAfter(from)) {
            errors.put("to", "La date de fin doit être après la date de début.");
        } else if (from != null && Duration.between(from, to).compareTo(SlotController.MAX_PERIOD) > 0) {
            errors.put("to", "La période ne peut pas dépasser 31 jours.");
        }
        failIfAny(errors);
        return AgendaMapper.toResponse(service.agenda(currentUser.id(), from, to));
    }

    /** Opens a slot in one of the caller's clinics (409 SLOT_OVERLAP if they already have one then). */
    @PostMapping("/slots")
    public ResponseEntity<AgendaItemDto> open(@RequestBody(required = false) OpenSlotRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (request == null || request.clinicId() == null) {
            errors.put("clinicId", "La clinique est obligatoire.");
        }
        if (request == null || request.startsAt() == null) {
            errors.put("startsAt", "La date de début est obligatoire.");
        }
        if (request == null || request.endsAt() == null) {
            errors.put("endsAt", "La date de fin est obligatoire.");
        }
        failIfAny(errors);
        Slot slot = service.open(currentUser.id(), request.clinicId(), request.startsAt(), request.endsAt());
        // After the commit: an audit entry is never written for a slot that was not saved
        audit.record(AuditAction.SLOT_OPENED, RESOURCE_SLOT, slot.getId().toString(), AuditOutcome.SUCCESS);
        return ResponseEntity.status(HttpStatus.CREATED).body(AgendaMapper.toSlotItem(slot));
    }

    /** Closes (CLOSED) or reopens (OPEN) one of the caller's slots; 409 SLOT_BOOKED if a patient holds it. */
    @PatchMapping("/slots/{id}")
    public AgendaItemDto changeStatus(@PathVariable String id, @RequestBody(required = false) SlotStatusRequest request) {
        UUID slotId = parseSlotId(id);
        UUID practitionerId = currentUser.id();
        // Ownership first: a slot that is not the caller's is a 403 whatever the body says
        service.ownSlot(practitionerId, slotId);
        SlotStatus target = targetStatus(request);
        SlotChange change = service.setStatus(practitionerId, slotId, target);
        if (change.change() == SlotChange.Change.CLOSED) {
            audit.record(AuditAction.SLOT_CLOSED, RESOURCE_SLOT, slotId.toString(), AuditOutcome.SUCCESS);
        } else if (change.change() == SlotChange.Change.OPENED) {
            audit.record(AuditAction.SLOT_OPENED, RESOURCE_SLOT, slotId.toString(), AuditOutcome.SUCCESS);
        }
        return AgendaMapper.toSlotItem(change.slot());
    }

    private static UUID parseSlotId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException notAnId) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains champs sont invalides.",
                    Map.of("id", "Identifiant invalide."));
        }
    }

    /** Only {@code OPEN} and {@code CLOSED}: a client can never choose {@code BOOKED}, which a booking sets. */
    private static SlotStatus targetStatus(SlotStatusRequest request) {
        String wanted = request == null ? null : request.status();
        if (AgendaMapper.OPEN.equals(wanted)) {
            return SlotStatus.FREE;
        }
        if (AgendaMapper.CLOSED.equals(wanted)) {
            return SlotStatus.CLOSED;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains champs sont invalides.",
                Map.of("status", "Le statut doit être OPEN ou CLOSED."));
    }

    private static void failIfAny(Map<String, String> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains champs sont invalides.", errors);
        }
    }

    /** A date Spring cannot read ({@code from=demain}): our error body with the field's name, local to this controller. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> unreadableParameter(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(ErrorBody.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Certains champs sont invalides.",
                Map.of(ex.getName(), "Date invalide (format attendu : 2026-10-27T09:00:00Z).")));
    }
}
