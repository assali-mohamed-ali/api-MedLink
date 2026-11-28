package com.example.apimedlink.controller;

import com.example.apimedlink.security.CurrentUser;
import com.example.apimedlink.security.RecentPatientLogin;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.PatientEmailChangeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/patients/me/email-change")
@PreAuthorize("hasRole('" + Roles.PATIENT + "')")
public class PatientEmailChangeController {
    private final PatientEmailChangeService changes;
    private final CurrentUser current;
    private final RecentPatientLogin recent;

    public PatientEmailChangeController(PatientEmailChangeService changes, CurrentUser current, RecentPatientLogin recent) {
        this.changes = changes;
        this.current = current;
        this.recent = recent;
    }

    public record Request(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String currentPassword) {
        @Override public String toString() { return "EmailChangeRequest[***]"; }
    }

    public record Confirmation(@NotBlank @Size(min = 43, max = 43) String code) {
        @Override public String toString() { return "EmailChangeConfirmation[***]"; }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void request(@Valid @RequestBody Request body, HttpServletRequest request) {
        recent.require(request);
        changes.request(current.id(), body.email(), body.currentPassword());
    }

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public java.util.Map<String, String> confirm(@Valid @RequestBody Confirmation body, HttpServletRequest request) {
        recent.require(request);
        changes.confirm(current.id(), body.code());
        return java.util.Map.of("status", "ACCEPTED");
    }
}
