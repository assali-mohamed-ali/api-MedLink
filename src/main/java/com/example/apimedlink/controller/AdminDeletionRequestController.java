package com.example.apimedlink.controller;

import com.example.apimedlink.dto.DecideDeletionRequest;
import com.example.apimedlink.dto.DeletionReviewDto;
import com.example.apimedlink.mapper.DeletionRequestMapper;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.DeletionReviewService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-16: the administrative staff handle the patients' deletion requests. {@code /api/admin/**} is kept to staff by
 * SecurityConfig before anything is read. Approving a request deletes nothing yet.
 */
@RestController
@RequestMapping("/api/admin/deletion-requests")
@PreAuthorize("hasRole('" + Roles.ADMINISTRATIVE_STAFF + "')")
public class AdminDeletionRequestController {

    private final DeletionReviewService service;

    public AdminDeletionRequestController(DeletionReviewService service) {
        this.service = service;
    }

    @Operation(summary = "The pending deletion requests, oldest first, with the retention period of each patient")
    @GetMapping
    public List<DeletionReviewDto> pending() {
        return service.pending().stream().map(DeletionRequestMapper::toDto).toList();
    }

    @Operation(summary = "Approve (409 while the retention period runs) or refuse (note required) a pending request")
    @PostMapping("/{patientId}/decision")
    public DeletionReviewDto decide(@PathVariable UUID patientId, @Valid @RequestBody DecideDeletionRequest request) {
        return DeletionRequestMapper.toDto(
                service.decide(patientId, DeletionRequestMapper.toStatus(request.decision()), request.note()));
    }
}
