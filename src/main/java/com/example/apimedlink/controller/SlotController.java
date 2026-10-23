package com.example.apimedlink.controller;

import com.example.apimedlink.dto.SlotSearchResponse;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.mapper.SlotMapper;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.SlotService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint 25.2 (MLink-98): a patient searches the free slots. */
@RestController
@RequestMapping("/api/slots")
public class SlotController {

    private final SlotService service;

    public SlotController(SlotService service) {
        this.service = service;
    }

    /**
     * {@code from} and {@code to} are required, but checked here rather than by Spring: Spring checks a
     * required parameter before {@code @PreAuthorize}, so a practitioner would get 400 instead of 403.
     * The other rules on the input (range, page size, French messages) come with MLink-100.
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
        Map<String, String> missing = new LinkedHashMap<>();
        if (from == null) {
            missing.put("from", "La date de début est obligatoire.");
        }
        if (to == null) {
            missing.put("to", "La date de fin est obligatoire.");
        }
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains champs sont invalides.", missing);
        }
        return SlotMapper.toSearchResponse(
                service.searchFree(from, to, clinicId, practitionerId, specialtyId, page, size));
    }
}
