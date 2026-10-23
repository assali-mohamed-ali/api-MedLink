package com.example.apimedlink.service;

import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Endpoint 11: staff force a user to choose a new password at the next login.
 *
 * <p>UPDATE_PASSWORD first, so no new login gets past it, then the open sessions. Both steps are
 * idempotent: a failure in either answers 503 and staff send the reset again. Nothing is rolled
 * back, since undoing the reset is the unsafe side.
 */
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
