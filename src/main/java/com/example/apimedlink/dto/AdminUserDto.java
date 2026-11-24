package com.example.apimedlink.dto;

import java.util.List;
import java.util.UUID;

/**
 * One account as staff see it. {@code id} is the Keycloak user id. {@code role} is the account's MedLink role, or
 * null when it has none or {@code roles} holds several (an inconsistent account: staff must repair it).
 * {@code roles} lists every MedLink role the account holds.
 */
public record AdminUserDto(
        UUID id,
        String email,
        String firstName,
        String lastName,
        String role,
        List<String> roles,
        boolean ambiguousRole,
        boolean enabled,
        boolean emailVerified) {}
