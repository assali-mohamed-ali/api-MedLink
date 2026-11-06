package com.example.apimedlink.dto;

import java.util.UUID;

/** One account as staff see it. {@code id} is the Keycloak user id; {@code role} is null when the user is in no MedLink group. */
public record AdminUserDto(
        UUID id,
        String email,
        String firstName,
        String lastName,
        String role,
        boolean enabled,
        boolean emailVerified) {}
        