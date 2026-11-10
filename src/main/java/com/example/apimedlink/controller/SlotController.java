package com.example.apimedlink.controller;

import com.example.apimedlink.dto.SlotFiltersResponse;
import com.example.apimedlink.dto.SlotSearchResponse;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.mapper.SlotMapper;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.SlotService;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
     * Every parameter arrives as text and is read here, after {@code @PreAuthorize}: Spring converts typed or
     * required parameters before method security runs, so a practitioner sending {@code clinicId=abc} would get
     * 400 instead of 403 (review on #87). Every broken rule is listed in {@code details}, under its field, in French.
     */
    @GetMapping
    @PreAuthorize("hasRole('" + Roles.PATIENT + "')")
    public SlotSearchResponse search(
            @RequestParam(name = "from", required = false) String fromText,
            @RequestParam(name = "to", required = false) String toText,
            @RequestParam(name = "clinicId", required = false) String clinicIdText,
            @RequestParam(name = "practitionerId", required = false) String practitionerIdText,
            @RequestParam(name = "specialtyId", required = false) String specialtyIdText,
            @RequestParam(name = "page", defaultValue = "0") String pageText,
            @RequestParam(name = "size", defaultValue = "20") String sizeText) {
        Map<String, String> errors = new LinkedHashMap<>();
        Instant from = instant("from", fromText, "La date de début est obligatoire.", errors);
        Instant to = instant("to", toText, "La date de fin est obligatoire.", errors);
        UUID clinicId = uuid("clinicId", clinicIdText, errors);
        UUID practitionerId = uuid("practitionerId", practitionerIdText, errors);
        UUID specialtyId = uuid("specialtyId", specialtyIdText, errors);
        Integer page = integer("page", pageText, errors);
        Integer size = integer("size", sizeText, errors);
        if (from != null && to != null && !to.isAfter(from)) {
            errors.put("to", "La date de fin doit être après la date de début.");
        } else if (from != null && to != null && Duration.between(from, to).compareTo(MAX_PERIOD) > 0) {
            errors.put("to", "La période ne peut pas dépasser 31 jours.");
        }
        if (page != null && page < 0) {
            errors.put("page", "Le numéro de page ne peut pas être négatif.");
        }
        if (size != null && (size < 1 || size > MAX_PAGE_SIZE)) {
            errors.put("size", "La taille de page doit être comprise entre 1 et 50.");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains champs sont invalides.", errors);
        }
        return SlotMapper.toSearchResponse(
                service.searchFree(from, to, clinicId, practitionerId, specialtyId, page, size));
    }

    /** The lists of the search form (MLink-163): clinics, specialties, practitioners with their clinics. */
    @GetMapping("/filters")
    @PreAuthorize("hasRole('" + Roles.PATIENT + "')")
    public SlotFiltersResponse filters() {
        return SlotMapper.toFiltersResponse(service.filters());
    }

    /** A required ISO-8601 instant ({@code 2026-10-27T09:00:00Z}); null and an error when missing or unreadable. */
    private static Instant instant(String field, String text, String missing, Map<String, String> errors) {
        if (text == null || text.isBlank()) {
            errors.put(field, missing);
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            errors.put(field, "Date invalide (format attendu : 2026-10-27T09:00:00Z).");
            return null;
        }
    }

    /** An optional id: null when absent, null and an error when unreadable. */
    private static UUID uuid(String field, String text, Map<String, String> errors) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            errors.put(field, "Identifiant invalide.");
            return null;
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
}
