package com.example.apimedlink.service;

import java.util.UUID;

/** What the API reads about a user from Keycloak. */
public record KeycloakUser(
        UUID id, String email, String firstName, String lastName, boolean enabled, boolean emailVerified) {}
