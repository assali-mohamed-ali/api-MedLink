package com.example.apimedlink.dto;

import com.example.apimedlink.security.Roles;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Body of PUT /api/admin/users/{id}/role: a staff role only, never PATIENT. */
public record ChangeRoleRequest(
        @NotBlank(message = "Le rôle est obligatoire.")
        @Pattern(regexp = "^(" + Roles.PRACTITIONER + "|" + Roles.ADMINISTRATIVE_STAFF + ")$",
                message = "Le rôle doit être PRACTITIONER ou ADMINISTRATIVE_STAFF.")
        String role) {}
        