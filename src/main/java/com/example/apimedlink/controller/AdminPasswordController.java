package com.example.apimedlink.controller;

import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.PasswordResetService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint 11 (MLink-88): staff force a password change; the user's Keycloak sessions end. */
@RestController
@RequestMapping("/api/admin/users")
public class AdminPasswordController {

    private final PasswordResetService resets;

    public AdminPasswordController(PasswordResetService resets) {
        this.resets = resets;
    }

    @PostMapping("/{id}/force-password-reset")
    @PreAuthorize("hasRole('" + Roles.ADMINISTRATIVE_STAFF + "')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void force(@PathVariable UUID id) {
        resets.force(id);
    }
}
