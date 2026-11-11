package com.example.apimedlink.dto;

import com.example.apimedlink.security.Roles;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

/**
 * Body of POST /api/admin/users. No id and no password: the id comes from Keycloak, the first password
 * is set by the person at activation. The role is a staff role: patients register themselves. A practitioner
 * also needs a specialty and the clinics they work in; they are ignored for a staff member.
 */
public record CreateUserRequest(
        @NotBlank(message = "L'adresse e-mail est obligatoire.")
        @Email(message = "L'adresse e-mail n'est pas valide.")
        @Size(max = 254, message = "L'adresse e-mail est trop longue.")
        String email,

        @NotBlank(message = "Le prénom est obligatoire.")
        @Size(max = 100, message = "Le prénom est trop long (100 caractères au plus).")
        String firstName,

        @NotBlank(message = "Le nom est obligatoire.")
        @Size(max = 100, message = "Le nom est trop long (100 caractères au plus).")
        String lastName,

        @NotBlank(message = "Le rôle est obligatoire.")
        @Pattern(regexp = "^(" + Roles.PRACTITIONER + "|" + Roles.ADMINISTRATIVE_STAFF + ")$",
                message = "Le rôle doit être PRACTITIONER ou ADMINISTRATIVE_STAFF.")
        String role,

        UUID specialtyId,

        @Size(max = 20, message = "Trop de cliniques.")
        Set<UUID> clinicIds) {

    /** Spaces around a pasted email are not an error. Runs before validation. */
    public CreateUserRequest {
        email = email == null ? null : email.trim();
    }
}
