package com.example.apimedlink.controller;

import com.example.apimedlink.dto.NotificationPreferencesDto;
import com.example.apimedlink.dto.PatientProfileDto;
import com.example.apimedlink.dto.UpdateNotificationPreferencesRequest;
import com.example.apimedlink.dto.UpdatePatientProfileRequest;
import com.example.apimedlink.mapper.PatientProfileMapper;
import com.example.apimedlink.security.CurrentUser;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.PatientProfileService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** US-15: the patient reads and edits their own phone and address. The patient is the caller, never an id. */
@RestController
@RequestMapping("/api/patients/me")
@PreAuthorize("hasRole('" + Roles.PATIENT + "')")
public class PatientProfileController {

    private final PatientProfileService service;
    private final CurrentUser currentUser;

    public PatientProfileController(PatientProfileService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @Operation(summary = "The caller's own phone and address")
    @GetMapping
    public PatientProfileDto get() {
        return PatientProfileMapper.toDto(service.get(currentUser.id()));
    }

    @Operation(summary = "Update the caller's own phone and address (a blank value clears it)")
    @PutMapping
    public PatientProfileDto update(@Valid @RequestBody UpdatePatientProfileRequest request) {
        return PatientProfileMapper.toDto(service.update(
                currentUser.id(),
                PatientProfileMapper.clean(request.phone()),
                PatientProfileMapper.clean(request.address())));
    }

    @Operation(summary = "The caller's notification preferences (reminders, new results)")
    @GetMapping("/notification-preferences")
    public NotificationPreferencesDto preferences() {
        return PatientProfileMapper.toPreferences(service.get(currentUser.id()));
    }

    @Operation(summary = "Replace the caller's notification preferences; both values are required")
    @PutMapping("/notification-preferences")
    public NotificationPreferencesDto updatePreferences(@Valid @RequestBody UpdateNotificationPreferencesRequest request) {
        return PatientProfileMapper.toPreferences(service.updatePreferences(currentUser.id(),
                request.appointmentReminders(), request.examResults()));
    }
}
