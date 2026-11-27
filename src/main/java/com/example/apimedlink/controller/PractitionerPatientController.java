package com.example.apimedlink.controller;

import com.example.apimedlink.dto.PatientSummaryDto;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.mapper.PatientSummaryMapper;
import com.example.apimedlink.security.CurrentUser;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.PatientSearchService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-22: a practitioner searches their own patients by name. The practitioner is the caller; SecurityConfig keeps
 * {@code /api/practitioner/**} to practitioners before anything is read.
 */
@RestController
@RequestMapping("/api/practitioner/patients")
@PreAuthorize("hasRole('" + Roles.PRACTITIONER + "')")
public class PractitionerPatientController {

    static final int MAX_QUERY = 100;

    private final PatientSearchService service;
    private final CurrentUser currentUser;

    public PractitionerPatientController(PatientSearchService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @Operation(summary = "The caller's linked patients whose name contains q (50 at most, by name)")
    @GetMapping
    public List<PatientSummaryDto> search(@RequestParam(required = false, defaultValue = "") String q) {
        if (q.length() > MAX_QUERY) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains champs sont invalides.",
                    Map.of("q", "La recherche est trop longue (100 caractères au plus)."));
        }
        return service.search(currentUser.id(), q).stream().map(PatientSummaryMapper::toDto).toList();
    }
}
