package com.example.apimedlink.service;

import java.util.UUID;
import org.springframework.stereotype.Service;

/** Endpoint 11: staff force a user to choose a new password at the next login. */
@Service
public class PasswordResetService {

    private final KeycloakAdminService keycloak;

    public PasswordResetService(KeycloakAdminService keycloak) {
        this.keycloak = keycloak;
    }

    public void force(UUID userId) {
        keycloak.requirePasswordChange(userId);
        keycloak.endSessions(userId);
    }
}
