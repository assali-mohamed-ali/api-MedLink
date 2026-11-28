package com.example.apimedlink.controller;

import com.example.apimedlink.dto.AbsenceDecisionDto;
import com.example.apimedlink.dto.AbsenceDto;
import com.example.apimedlink.dto.DecideAbsenceAppointmentRequest;
import com.example.apimedlink.dto.DeclareAbsenceRequest;
import com.example.apimedlink.mapper.AbsenceMapper;
import com.example.apimedlink.security.CurrentUser;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.AbsenceService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-21: a practitioner declares absences and decides, appointment by appointment, to cancel or move to next week. The
 * practitioner is always the caller. {@code SecurityConfig} keeps {@code /api/practitioner/**} to practitioners before
 * anything is read.
 */
@RestController
@RequestMapping("/api/practitioner")
@PreAuthorize("hasRole('" + Roles.PRACTITIONER + "')")
public class PractitionerAbsenceController {

    private final AbsenceService service;
    private final CurrentUser currentUser;

    public PractitionerAbsenceController(AbsenceService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Declare an absence: closes the free slots it covers, lists the booked appointments to decide")
    @PostMapping("/absences")
    @ResponseStatus(HttpStatus.CREATED)
    public AbsenceDto declare(@Valid @RequestBody DeclareAbsenceRequest request) {
        return AbsenceMapper.toDto(service.declare(currentUser.id(), request.startsAt(), request.endsAt(), request.reason()));
    }

    @Operation(summary = "The caller's absences, latest first")
    @GetMapping("/absences")
    public List<AbsenceDto> mine() {
        return service.mine(currentUser.id()).stream().map(AbsenceMapper::toDto).toList();
    }

    @Operation(summary = "The booked appointments of an absence still waiting for a decision")
    @GetMapping("/absences/{absenceId}/appointments")
    public List<AbsenceDto.ToDecide> pending(@PathVariable UUID absenceId) {
        return AbsenceMapper.toDtos(service.pending(currentUser.id(), absenceId));
    }

    @Operation(summary = "Cancel an appointment of an absence, or move it to the same time next week; the patient is mailed")
    @PostMapping("/appointments/{appointmentId}/absence-decision")
    public AbsenceDecisionDto decide(@PathVariable UUID appointmentId,
            @Valid @RequestBody DecideAbsenceAppointmentRequest request) {
        return AbsenceMapper.toDto(
                service.decide(currentUser.id(), appointmentId, AbsenceMapper.toAction(request.action())));
    }
}
