package com.example.apimedlink.controller;

import com.example.apimedlink.dto.SlotSearchResponse;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.exception.ErrorBody;
import com.example.apimedlink.mapper.SlotMapper;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.SlotService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Endpoint 25.2 (MLink-98): a patient searches the free slots; its input rules are MLink-100 (25.4). */
@RestController
@RequestMapping("/api/slots")
public class SlotController {

    /** A search looks one month ahead at most: bounds the work one request can ask of the database. */
    static final Duration MAX_PERIOD = Duration.ofDays(31);

    static final int MAX_PAGE_SIZE = 50;

    private final SlotService service;

    public SlotController(SlotService service) {
        this.service = service;
    }

    /**
     * {@code from} and {@code to} are required, but checked here rather than by Spring: Spring checks a
     * required parameter before {@code @PreAuthorize}, so a practitioner would get 400 instead of 403.
     * Every broken rule is listed in {@code details}, under its field, in French.
     */
    @GetMapping
    @PreAuthorize("hasRole('" + Roles.PATIENT + "')")
    public SlotSearchResponse search(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) UUID clinicId,
            @RequestParam(required = false) UUID practitionerId,
            @RequestParam(required = false) UUID specialtyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (from == null) {
            errors.put("from", "La date de début est obligatoire.");
        }
        if (to == null) {
            errors.put("to", "La date de fin est obligatoire.");
        } else if (from != null && !to.isAfter(from)) {
            errors.put("to", "La date de fin doit être après la date de début.");
        } else if (from != null && Duration.between(from, to).compareTo(MAX_PERIOD) > 0) {
            errors.put("to", "La période ne peut pas dépasser 31 jours.");
        }
        if (page < 0) {
            errors.put("page", "Le numéro de page ne peut pas être négatif.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            errors.put("size", "La taille de page doit être comprise entre 1 et 50.");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains champs sont invalides.", errors);
        }
        return SlotMapper.toSearchResponse(
                service.searchFree(from, to, clinicId, practitionerId, specialtyId, page, size));
    }

    /**
     * A parameter Spring cannot read ({@code clinicId=abc}, {@code from=demain}): our error body, with the
     * field's name. Local to this controller, so the shared exception handler stays unchanged.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> unreadableParameter(MethodArgumentTypeMismatchException ex) {
        Class<?> type = ex.getRequiredType();
        String message = type == UUID.class ? "Identifiant invalide."
                : type == Instant.class ? "Date invalide (format attendu : 2026-10-27T09:00:00Z)."
                : "Nombre entier attendu.";
        return ResponseEntity.badRequest().body(ErrorBody.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Certains champs sont invalides.", Map.of(ex.getName(), message)));
    }
}
