package com.example.apimedlink.dto;

import com.example.apimedlink.security.Roles;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

/**
 * Body of PUT /api/admin/users/{id}/role: a staff role only, never PATIENT. Becoming a practitioner also needs a
 * specialty and the clinics they work in (their profile); they are ignored otherwise.
 */
public record ChangeRoleRequest(
        @NotBlank(message = "Le rôle est obligatoire.")
        @Pattern(regexp = "^(" + Roles.PRACTITIONER + "|" + Roles.ADMINISTRATIVE_STAFF + ")$",
                message = "Le rôle doit être PRACTITIONER ou ADMINISTRATIVE_STAFF.")
        String role,

        UUID specialtyId,

        @Size(max = 20, message = "Trop de cliniques.")
        Set<UUID> clinicIds) {}
