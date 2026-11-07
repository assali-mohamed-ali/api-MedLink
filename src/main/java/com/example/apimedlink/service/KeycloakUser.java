package com.example.apimedlink.service;

import java.util.List;
import java.util.UUID;

/** What the API reads about a user from Keycloak. */
public record KeycloakUser(
        UUID id,
        String email,
        String firstName,
        String lastName,
        boolean enabled,
        boolean emailVerified,
        List<String> requiredActions) {

    public KeycloakUser(UUID id, String email, String firstName, String lastName, boolean enabled, boolean emailVerified) {
        this(id, email, firstName, lastName, enabled, emailVerified, List.of());
    }
}
