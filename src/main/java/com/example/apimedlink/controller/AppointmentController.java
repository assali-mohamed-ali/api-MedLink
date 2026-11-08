package com.example.apimedlink.controller;

import com.example.apimedlink.dto.AppointmentDto;
import com.example.apimedlink.dto.BookAppointmentRequest;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.mapper.AppointmentMapper;
import com.example.apimedlink.model.Appointment;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.security.CurrentUser;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.AppointmentService;
import com.example.apimedlink.service.AuditService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A patient's appointments (epic MLink-8): booking is endpoint 27.1 (MLink-102). */
@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

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
        String text = request == null ? null : request.slotId();
        if (text == null || text.isBlank()) {
            throw invalid("Le créneau est obligatoire.");
        }
        UUID slotId;
        try {
            slotId = UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw invalid("Identifiant invalide.");
        }
        Appointment appointment = service.book(currentUser.id(), slotId);
        // MLink-104: recorded once the booking is committed, so a refused or rolled-back booking leaves no SUCCESS;
        // ids only (the caller is taken from the session by AuditService)
        audit.record(AuditAction.APPOINTMENT_BOOKED, "APPOINTMENT", appointment.getId().toString(), AuditOutcome.SUCCESS);
        return AppointmentMapper.toDto(appointment);
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains champs sont invalides.",
                Map.of("slotId", message));
    }
}
