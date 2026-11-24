package com.example.apimedlink.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Body of POST /api/auth/register. No id and no role: the id comes from Keycloak, the role is
 * always PATIENT. The password rules are Keycloak's; only blank and absurd lengths stop here.
 */
public record RegisterRequest(
        @NotBlank(message = "L'adresse e-mail est obligatoire.")
        @Email(message = "L'adresse e-mail n'est pas valide.")
        @Size(max = 254, message = "L'adresse e-mail est trop longue.")
        String email,

        @NotBlank(message = "Le mot de passe est obligatoire.")
        @Size(max = 128, message = "Le mot de passe est trop long (128 caractères au plus).")
        String password,

        @NotBlank(message = "Le prénom est obligatoire.")
        @Size(max = 100, message = "Le prénom est trop long (100 caractères au plus).")
        String firstName,

        @NotBlank(message = "Le nom est obligatoire.")
        @Size(max = 100, message = "Le nom est trop long (100 caractères au plus).")
        String lastName,

        @NotNull(message = "La date de naissance est obligatoire.")
        @Past(message = "La date de naissance doit être dans le passé.")
        LocalDate birthDate,

        @Size(max = 30, message = "Le numéro de téléphone est trop long.")
        @Pattern(regexp = "^[+]?[0-9 ]*$", message = "Le numéro de téléphone ne peut contenir que des chiffres, des espaces et un + au début.")
        String phone,

        @Size(max = 255, message = "L'adresse est trop longue (255 caractères au plus).")
        String address) {

    /** Spaces around a pasted email are not an error. Runs before validation. */
    public RegisterRequest {
        email = email == null ? null : email.trim();
    }

    /** Never print the password, whoever logs this object. */
    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", password=***, firstName=" + firstName + ", lastName=" + lastName
                + ", birthDate=" + birthDate + ", phone=" + phone + ", address=" + address + "]";
    }
}
