package com.example.apimedlink.controller;

import com.example.apimedlink.dto.PatientExportDto;
import com.example.apimedlink.mapper.PatientExportMapper;
import com.example.apimedlink.security.CurrentUser;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.PatientExportService;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** US-16: the patient downloads their own data as JSON. The patient is the caller, never an id in the URL. */
@RestController
@RequestMapping("/api/patients/me/export")
@PreAuthorize("hasRole('" + Roles.PATIENT + "')")
public class PatientExportController {

    private final PatientExportService service;
    private final CurrentUser currentUser;

    public PatientExportController(PatientExportService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Everything MedLink holds about the caller, as a JSON file")
    @GetMapping
    public ResponseEntity<PatientExportDto> export() {
        PatientExportDto body = PatientExportMapper.toDto(service.export(currentUser.id()), Instant.now());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("medlink-export.json").build().toString())
                .body(body);
    }
}
