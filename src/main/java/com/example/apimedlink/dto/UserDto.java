package com.example.apimedlink.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** {@code id} is the Keycloak user id. */
public record UserDto(
        UUID id,
        @NotBlank @Size(max = 254) String username,
        @NotBlank @Email @Size(max = 254) String email) {}
