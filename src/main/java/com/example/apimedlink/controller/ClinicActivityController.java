package com.example.apimedlink.controller;

import com.example.apimedlink.dto.AdminPractitionerDto;
import com.example.apimedlink.dto.AgendaResponse;
import com.example.apimedlink.dto.ProfileChoicesDto.ClinicItem;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.exception.ErrorBody;
import com.example.apimedlink.mapper.AgendaMapper;
import com.example.apimedlink.mapper.ClinicActivityMapper;
import com.example.apimedlink.model.AppointmentStatus;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.ClinicActivityService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * MLink-35: staff supervise the clinics' activity, read only: the practitioners, and any one's agenda (the same items
 * as the practitioner's own, with the patient's names on a booked slot, nothing medical). No route here changes
 * anything. The URL rule in {@code SecurityConfig} keeps {@code /api/admin/**} to staff before any parameter is read.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('" + Roles.ADMINISTRATIVE_STAFF + "')")
public class ClinicActivityController {

    private final ClinicActivityService service;

    public ClinicActivityController(ClinicActivityService service) {
        this.service = service;
    }

    @GetMapping("/practitioners")
    public List<AdminPractitionerDto> practitioners() {
        return ClinicActivityMapper.toDtos(service.practitioners());
    }

    @GetMapping("/clinics")
    public List<ClinicItem> clinics() {
        return service.clinics().stream().map(clinic ->
                new ClinicItem(clinic.getId(), clinic.getName(), clinic.getCity())).toList();
    }

    @GetMapping("/clinics/{clinicId}/activity")
    public Map<AppointmentStatus, Long> activity(@PathVariable UUID clinicId,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        Map<String, String> errors = new LinkedHashMap<>();
        validatePeriod(from, to, errors);
        rejectInvalidFields(errors);
        return service.activity(clinicId, from, to);
    }

    /** A practitioner's slots of the period (31 days at most), like GET /api/practitioner/agenda for themselves. */
    @GetMapping("/agenda")
    public AgendaResponse agenda(@RequestParam(required = false) UUID practitionerId,
                                 @RequestParam(required = false) Instant from,
                                 @RequestParam(required = false) Instant to) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (practitionerId == null) {
            errors.put("practitionerId", "Le praticien est obligatoire.");
        }
        validatePeriod(from, to, errors);
        rejectInvalidFields(errors);
        return AgendaMapper.toResponse(service.agenda(practitionerId, from, to));
    }

    private static void validatePeriod(Instant from, Instant to, Map<String, String> errors) {
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
    }

    private static void rejectInvalidFields(Map<String, String> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains champs sont invalides.", errors);
        }
    }

    /** An id or a date Spring cannot read: our error body with the field's name, local to this controller. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> unreadableParameter(MethodArgumentTypeMismatchException ex) {
        String message = switch (ex.getName()) {
            case "practitionerId" -> "Identifiant de praticien invalide.";
            case "clinicId" -> "Identifiant de clinique invalide.";
            default -> "Date invalide (format attendu : 2026-10-27T09:00:00Z).";
        };
        return ResponseEntity.badRequest().body(ErrorBody.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Certains champs sont invalides.", Map.of(ex.getName(), message)));
    }
}
