package com.example.apimedlink.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Endpoint 5. The email's format is not checked here: an unknown address gets the same 401 as a wrong password. */
public record LoginRequest(
        @NotBlank(message = "L'adresse e-mail est obligatoire.")
        @Size(max = 254, message = "L'adresse e-mail est trop longue.")
        String email,
        @NotBlank(message = "Le mot de passe est obligatoire.")
        @Size(max = 128, message = "Le mot de passe est trop long (128 caractères au plus).")
        String password) {

    /** Never print the password, whoever logs this object. */
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=***]";
    }
}
