package com.example.apimedlink.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * US-15: what a patient may change about themselves: phone and address only. No id, role, email, status or birth
 * date is read from the body. A blank value clears the field.
 */
public record UpdatePatientProfileRequest(
        @Size(max = 30, message = "Le numéro de téléphone est trop long.")
        @Pattern(regexp = "^ *[+]?[0-9 ]*$", message = "Le numéro de téléphone ne peut contenir que des chiffres, des espaces et un + au début.")
        String phone,

        @Size(max = 255, message = "L'adresse est trop longue (255 caractères au plus).")
        String address) {}
