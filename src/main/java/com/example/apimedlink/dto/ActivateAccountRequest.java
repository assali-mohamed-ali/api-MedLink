package com.example.apimedlink.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /api/auth/account/activate: the token from the mail and the first password (Keycloak's rules). */
public record ActivateAccountRequest(
        @NotBlank(message = "Le lien d'activation est obligatoire.")
        @Size(max = 200, message = "Le lien d'activation n'est pas valide.")
        String token,

        @NotBlank(message = "Le mot de passe est obligatoire.")
        @Size(max = 128, message = "Le mot de passe est trop long (128 caractères au plus).")
        String password) {

    /** Never print the token or the password, whoever logs this object. */
    @Override
    public String toString() {
        return "ActivateAccountRequest[token=***, password=***]";
    }
}
