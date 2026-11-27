package com.example.apimedlink.controller;

import com.example.apimedlink.dto.ReassignmentDto;
import com.example.apimedlink.mapper.ReassignmentMapper;
import com.example.apimedlink.security.CurrentUser;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.ReassignmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReassignmentController {
    private final ReassignmentService service;
    private final CurrentUser current;

    public ReassignmentController(ReassignmentService service, CurrentUser current) {
        this.service = service;
        this.current = current;
    }

    public record ProposeRequest(@NotNull UUID appointmentId, @NotNull UUID targetSlotId) {}
    public record DecisionRequest(Boolean accepted) {}

    @GetMapping("/api/admin/reassignments")
    @PreAuthorize("hasRole('" + Roles.ADMINISTRATIVE_STAFF + "')")
    public ReassignmentDto.Page<ReassignmentDto> staff(
            @RequestParam(defaultValue = "0") String page, @RequestParam(defaultValue = "20") String size) {
        PageParams p = PageParams.read(page, size);
        return ReassignmentMapper.page(service.staffList(p.page(), p.size()), ReassignmentMapper::proposal);
    }

    @GetMapping("/api/admin/reassignments/candidates")
    @PreAuthorize("hasRole('" + Roles.ADMINISTRATIVE_STAFF + "')")
    public ReassignmentDto.Page<ReassignmentDto.Candidate> candidates(@RequestParam UUID practitionerId,
            @RequestParam UUID clinicId, @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size) {
        PageParams p = PageParams.read(page, size);
        return ReassignmentMapper.page(service.candidates(practitionerId, clinicId, p.page(), p.size()),
                ReassignmentMapper::candidate);
    }

    @GetMapping("/api/admin/reassignments/targets")
    @PreAuthorize("hasRole('" + Roles.ADMINISTRATIVE_STAFF + "')")
    public ReassignmentDto.Page<ReassignmentDto.SlotRef> targets(@RequestParam UUID appointmentId,
            @RequestParam(defaultValue = "0") String page, @RequestParam(defaultValue = "20") String size) {
        PageParams p = PageParams.read(page, size);
        return ReassignmentMapper.page(service.targets(appointmentId, p.page(), p.size()), ReassignmentMapper::slot);
    }

    @PostMapping("/api/admin/reassignments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('" + Roles.ADMINISTRATIVE_STAFF + "')")
    public ReassignmentDto propose(@Valid @RequestBody ProposeRequest request) {
        return ReassignmentMapper.proposal(service.propose(current.id(), request.appointmentId(), request.targetSlotId()));
    }

    @GetMapping("/api/patients/me/reassignments")
    @PreAuthorize("hasRole('" + Roles.PATIENT + "')")
    public ReassignmentDto.Page<ReassignmentDto> patient(
            @RequestParam(defaultValue = "0") String page, @RequestParam(defaultValue = "20") String size) {
        PageParams p = PageParams.read(page, size);
        return ReassignmentMapper.page(service.mine(current.id(), ReassignmentService.Party.PATIENT, p.page(), p.size()),
                ReassignmentMapper::proposal);
    }

    @PostMapping("/api/patients/me/reassignments/{id}/decision")
    @PreAuthorize("hasRole('" + Roles.PATIENT + "')")
    public ReassignmentDto patientDecision(@PathVariable String id, @RequestBody DecisionRequest decision) {
        return ReassignmentMapper.proposal(service.decide(id(id), current.id(), ReassignmentService.Party.PATIENT,
                decision.accepted()));
    }

    @GetMapping("/api/practitioner/reassignments")
    @PreAuthorize("hasRole('" + Roles.PRACTITIONER + "')")
    public ReassignmentDto.Page<ReassignmentDto> practitioner(
            @RequestParam(defaultValue = "0") String page, @RequestParam(defaultValue = "20") String size) {
        PageParams p = PageParams.read(page, size);
        return ReassignmentMapper.page(service.mine(current.id(), ReassignmentService.Party.PRACTITIONER,
                p.page(), p.size()), ReassignmentMapper::proposal);
    }

    @PostMapping("/api/practitioner/reassignments/{id}/decision")
    @PreAuthorize("hasRole('" + Roles.PRACTITIONER + "')")
    public ReassignmentDto practitionerDecision(@PathVariable String id, @RequestBody DecisionRequest decision) {
        return ReassignmentMapper.proposal(service.decide(id(id), current.id(), ReassignmentService.Party.PRACTITIONER,
                decision.accepted()));
    }

    private static UUID id(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            throw new AccessDeniedException("Not the caller's reassignment");
        }
    }
}
