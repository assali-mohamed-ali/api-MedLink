package com.example.apimedlink.controller;

import com.example.apimedlink.dto.ReportDto;
import com.example.apimedlink.dto.ReportRequest;
import com.example.apimedlink.mapper.ReportMapper;
import com.example.apimedlink.security.CurrentUser;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.ConsultationReportService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MLink-32 (32.3): the consultation reports of a patient, for the practitioners the patient is attached to. The role
 * is also checked by the URL rule in {@code SecurityConfig}, before Spring reads the body: a patient gets 403, never
 * 400. The attachment is checked before the text: a practitioner who is not attached gets 403 whatever they send.
 */
@RestController
@RequestMapping("/api/patients")
@PreAuthorize("hasRole('" + Roles.PRACTITIONER + "')")
public class ConsultationReportController {

    private final ConsultationReportService service;
    private final CurrentUser currentUser;

    public ConsultationReportController(ConsultationReportService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Write a consultation report on a patient the practitioner has an appointment with")
    @PostMapping("/{id}/reports")
    public ResponseEntity<ReportDto> create(@PathVariable String id, @RequestBody(required = false) ReportRequest request) {
        ReportDto created = ReportMapper.toDto(
                service.create(currentUser.id(), patientId(id), request == null ? null : request.content()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "The consultation reports of a patient, newest first")
    @GetMapping("/{id}/reports")
    public List<ReportDto> list(@PathVariable String id) {
        return service.list(currentUser.id(), patientId(id)).stream().map(ReportMapper::toDto).toList();
    }

    /** An id that is not a UUID is nobody's attached patient: the same 403 as any other, never a 400. */
    private static UUID patientId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException notAnId) {
            throw new AccessDeniedException("Not a patient id");
        }
    }
}
