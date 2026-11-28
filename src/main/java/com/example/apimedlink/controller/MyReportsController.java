package com.example.apimedlink.controller;

import com.example.apimedlink.dto.MyReportDto;
import com.example.apimedlink.mapper.ReportMapper;
import com.example.apimedlink.security.CurrentUser;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.ConsultationReportService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MLink-32 follow-up: the patient reads the consultation reports written about them. Patients only; the patient is
 * always the caller, never an id in the URL (as {@code GET /api/appointments/me}).
 */
@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasRole('" + Roles.PATIENT + "')")
public class MyReportsController {

    private final ConsultationReportService service;
    private final CurrentUser currentUser;

    public MyReportsController(ConsultationReportService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @Operation(summary = "The caller's own consultation reports, newest first, with who wrote them")
    @GetMapping("/me")
    public List<MyReportDto> mine() {
        return service.mine(currentUser.id()).stream().map(ReportMapper::toDto).toList();
    }
}
