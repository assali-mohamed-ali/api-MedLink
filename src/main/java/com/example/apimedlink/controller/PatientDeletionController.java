package com.example.apimedlink.controller;

import com.example.apimedlink.dto.DeletionRequestDto;
import com.example.apimedlink.mapper.DeletionRequestMapper;
import com.example.apimedlink.model.DeletionRequest;
import com.example.apimedlink.security.CurrentUser;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.PatientDeletionService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-16: the patient asks for their data to be deleted, reads the state of the request, and takes it back. Recording
 * the request deletes nothing. The patient is the caller, never an id in the URL.
 */
@RestController
@RequestMapping("/api/patients/me/deletion-request")
@PreAuthorize("hasRole('" + Roles.PATIENT + "')")
public class PatientDeletionController {

    private final PatientDeletionService service;
    private final CurrentUser currentUser;

    public PatientDeletionController(PatientDeletionService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @Operation(summary = "The state of the caller's deletion request")
    @GetMapping
    public DeletionRequestDto state() {
        return toDto(service.current(currentUser.id()));
    }

    @Operation(summary = "Ask for the caller's data to be deleted (records the request only)")
    @PostMapping
    public DeletionRequestDto request() {
        return toDto(Optional.of(service.request(currentUser.id())));
    }

    @Operation(summary = "Take the deletion request back")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel() {
        service.cancel(currentUser.id());
    }

    private DeletionRequestDto toDto(Optional<DeletionRequest> request) {
        return DeletionRequestMapper.toDto(request, request.map(service::dueAt).orElse(null));
    }
}
