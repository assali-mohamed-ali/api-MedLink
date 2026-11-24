package com.example.apimedlink.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResendVerificationRequest(
        @NotBlank(message = "L'adresse e-mail est obligatoire.")
        @Email(message = "L'adresse e-mail n'est pas valide.")
        @Size(max = 254, message = "L'adresse e-mail est trop longue.")
        String email) {

    /** Spaces around a pasted email are not an error. Runs before validation. */
    public ResendVerificationRequest {
        email = email == null ? null : email.trim();
    }
}
