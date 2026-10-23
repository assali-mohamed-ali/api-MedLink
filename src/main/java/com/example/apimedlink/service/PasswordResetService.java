package com.example.apimedlink.service;

import com.example.apimedlink.repository.SessionRevocationStore;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Endpoint 11: staff force a user to choose a new password at the next login.
 *
 * <p>UPDATE_PASSWORD first, so no new login gets past it; then the API refuses the old sessions
 * itself (session_revocations), whatever Keycloak does; then Keycloak signs them out. Every step is
 * idempotent: a failure answers 503 or 500 and staff send the reset again. Nothing is rolled back,
 * since undoing the reset is the unsafe side.
 */
@Service
public class PasswordResetService {

    private final KeycloakAdminService keycloak;
    private final SessionRevocationStore revocations;

    public PasswordResetService(KeycloakAdminService keycloak, SessionRevocationStore revocations) {
        this.keycloak = keycloak;
        this.revocations = revocations;
    }

    public void force(UUID userId) {
        keycloak.requirePasswordChange(userId);
        revocations.revoke(userId);
        keycloak.endSessions(userId);
    }
}
