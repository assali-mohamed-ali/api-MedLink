package com.example.apimedlink.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** US-03: the token of the mailed link and the new password (Keycloak's rules decide whether it is strong enough). */
public record ResetPasswordRequest(
        @NotBlank(message = "Le lien est incomplet.") @Size(max = 100, message = "Le lien est incomplet.") String token,
        @NotBlank(message = "Le nouveau mot de passe est obligatoire.")
        @Size(max = 128, message = "Le nouveau mot de passe est trop long (128 caractères au plus).")
        String newPassword) {

    /** Never print the password, whoever logs this object. */
    @Override
    public String toString() {
        return "ResetPasswordRequest[token=***, newPassword=***]";
    }
}
