package com.example.apimedlink.service;

import java.util.Optional;
import java.util.UUID;

/**
 * The only door to Keycloak's admin API, through the {@code medlink-admin} service account.
 *
 * <p>Errors: {@link com.example.apimedlink.exception.EmailTakenException} (409),
 * {@link com.example.apimedlink.exception.InvalidPasswordException} (password policy),
 * {@link jakarta.persistence.EntityNotFoundException} (unknown user),
 * {@link com.example.apimedlink.exception.KeycloakUnavailableException} (anything else, or no answer).
 */
public interface KeycloakAdminService {

    /** Creates an enabled user with {@code emailVerified=false} in the given group; returns its Keycloak id. */
    UUID createUser(String username, String email, String firstName, String lastName, String groupPath);

    /**
     * Sets a permanent (not temporary) password, checked against the realm's password policy.
     * Keycloak also removes UPDATE_PASSWORD in the same call and keeps any other required action.
     */
    void setPassword(UUID id, String password);

    void deleteUser(UUID id);

    void setEmailVerified(UUID id, boolean verified);

    KeycloakUser getUser(UUID id);

    /** Exact email lookup, used only after Keycloak accepted the password. */
    Optional<KeycloakUser> findByEmail(String email);

    /** Adds Keycloak's UPDATE_PASSWORD required action, keeping any other pending action. Idempotent. */
    void requirePasswordChange(UUID id);

    /** Ends every open Keycloak session of this user. Idempotent. */
    void endSessions(UUID id);
}
