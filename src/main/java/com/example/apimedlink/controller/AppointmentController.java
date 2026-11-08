package com.example.apimedlink.controller;

import com.example.apimedlink.dto.AppointmentDto;
import com.example.apimedlink.dto.AppointmentPageResponse;
import com.example.apimedlink.dto.BookAppointmentRequest;
import com.example.apimedlink.dto.RescheduleAppointmentRequest;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.mapper.AppointmentMapper;
import com.example.apimedlink.model.Appointment;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.security.CurrentUser;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.AppointmentService;
import com.example.apimedlink.service.AuditService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A patient's appointments (epic MLink-8): booking is endpoint 27.1 (MLink-102). */
@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    static final int MAX_PAGE_SIZE = 50;

    private final AppointmentService service;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public AppointmentController(AppointmentService service, CurrentUser currentUser, AuditService audit) {
        this.service = service;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    /**
     * The caller books a slot for themselves. {@code slotId} is read here, after {@code @PreAuthorize}: a typed or
     * validated body would be checked before the role, and a practitioner would get 400 instead of 403.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('" + Roles.PATIENT + "')")
    public AppointmentDto book(@RequestBody(required = false) BookAppointmentRequest request) {
        UUID slotId = slotId("slotId", request == null ? null : request.slotId(), "Le créneau est obligatoire.");
        Appointment appointment = service.book(currentUser.id(), slotId);
        // MLink-104: recorded once the booking is committed, so a refused or rolled-back booking leaves no SUCCESS;
        // ids only (the caller is taken from the session by AuditService)
        audit.record(AuditAction.APPOINTMENT_BOOKED, "APPOINTMENT", appointment.getId().toString(), AuditOutcome.SUCCESS);
        return AppointmentMapper.toDto(appointment);
    }

    /**
     * MLink-108: the caller's own appointments, latest first, cancelled ones included; never an id in the URL.
     * {@code page} and {@code size} are text, read after the role check, with the slot search's rules.
     */
    @GetMapping("/me")
    @PreAuthorize("hasRole('" + Roles.PATIENT + "')")
    public AppointmentPageResponse mine(
            @RequestParam(name = "page", defaultValue = "0") String pageText,
            @RequestParam(name = "size", defaultValue = "20") String sizeText) {
        Map<String, String> errors = new LinkedHashMap<>();
        Integer page = integer("page", pageText, errors);
        Integer size = integer("size", sizeText, errors);
        if (page != null && page < 0) {
            errors.put("page", "Le numéro de page ne peut pas être négatif.");
        }
        if (size != null && (size < 1 || size > MAX_PAGE_SIZE)) {
            errors.put("size", "La taille de page doit être comprise entre 1 et 50.");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains champs sont invalides.", errors);
        }
        return AppointmentMapper.toPageResponse(service.mine(currentUser.id(), page, size));
    }

    /**
     * MLink-106: the holder cancels their appointment (204). {@code id} is text: an unreadable one is refused like an
     * unknown one or someone else's, with the same 403, so nothing tells whether an appointment exists.
     */
    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('" + Roles.PATIENT + "')")
    public void cancel(@PathVariable("id") String idText) {
        service.cancel(appointmentId(idText));
    }

    /**
     * MLink-107: the holder moves their appointment to another free slot (200, the new appointment). An unreadable
     * {@code id} is the same 403 as for a cancel; then {@code newSlotId} is checked (400), then the move.
     */
    @PostMapping("/{id}/reschedule")
    @PreAuthorize("hasRole('" + Roles.PATIENT + "')")
    public AppointmentDto reschedule(@PathVariable("id") String idText,
            @RequestBody(required = false) RescheduleAppointmentRequest request) {
        UUID id = appointmentId(idText);
        UUID newSlotId = slotId("newSlotId", request == null ? null : request.newSlotId(),
                "Le nouveau créneau est obligatoire.");
        return AppointmentMapper.toDto(service.reschedule(id, newSlotId));
    }

    /** An appointment id from the path: unreadable is refused like unknown (403), never telling it apart. */
    private static UUID appointmentId(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new AccessDeniedException("Unreadable appointment id");
        }
    }

    /** A slot id from the body: missing or unreadable is a 400 under its field. */
    private static UUID slotId(String field, String text, String missing) {
        if (text == null || text.isBlank()) {
            throw invalid(field, missing);
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw invalid(field, "Identifiant invalide.");
        }
    }

    private static Integer integer(String field, String text, Map<String, String> errors) {
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            errors.put(field, "Nombre entier attendu.");
            return null;
        }
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains champs sont invalides.",
                Map.of(field, message));
    }
}
