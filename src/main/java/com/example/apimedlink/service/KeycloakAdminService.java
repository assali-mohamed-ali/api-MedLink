package com.example.apimedlink.service;

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

    /** Sets a permanent (not temporary) password, checked against the realm's password policy. */
    void setPassword(UUID id, String password);

    void deleteUser(UUID id);

    void setEmailVerified(UUID id, boolean verified);

    KeycloakUser getUser(UUID id);
}
