package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.exception.KeycloakUnavailableException;
import com.example.apimedlink.exception.PasswordChangeRequiredException;
import com.example.apimedlink.repository.SessionRevocationStore;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Endpoint 10: the current password is checked by Keycloak (password grant), then the new one is
 * set through the admin API. A change ends every Keycloak session of the user (the check's one
 * too); the controller closes the caller's API session, the others follow at their next renewal.
 */
@Service
public class PasswordService {

    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);

    private final KeycloakAdminService keycloak;
    private final KeycloakLoginClient login;
    private final SessionRevocationStore revocations;

    public PasswordService(KeycloakAdminService keycloak, KeycloakLoginClient login,
                           SessionRevocationStore revocations) {
        this.keycloak = keycloak;
        this.login = login;
        this.revocations = revocations;
    }

    public void change(UUID userId, String currentPassword, String newPassword) {
        validateNewPassword(newPassword);
        if (currentPassword == null || currentPassword.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Le mot de passe actuel est obligatoire.", Map.of(
                            "currentPassword", "Le mot de passe actuel est obligatoire."));
        }
        KeycloakUser user = keycloak.getUser(userId);
        KeycloakTokens tokens = currentPassword(user, currentPassword);
        try {
            keycloak.setPassword(userId, newPassword);
        } catch (RuntimeException refused) {
            login.endSession(tokens.refreshToken());
            throw refused;
        }
        endSessions(userId);
    }

    /**
     * The forced branch: the pending login already proved the current password. One Keycloak write:
     * a permanent password also drops UPDATE_PASSWORD (Keycloak 26), so nothing is left half done.
     */
    public void changeForced(UUID userId, String newPassword) {
        validateNewPassword(newPassword);
        keycloak.setPassword(userId, newPassword);
    }

    /**
     * The new password is set and cannot be put back (password history), so a failure here does not
     * undo the change: it is logged for an admin to sign the user out in the console.
     */
    private void endSessions(UUID userId) {
        try {
            keycloak.endSessions(userId);
        } catch (KeycloakUnavailableException e) {
            log.error("Password changed for user {} but its Keycloak sessions could not be ended: "
                    + "sign the user out in the Keycloak console", userId);
        }
    }

    /**
     * Checked here, not with Bean Validation: the controller must first tell a pending session from
     * no session, so an anonymous {@code {}} must be 401, not 400.
     */
    private static void validateNewPassword(String newPassword) {
        if (newPassword == null || newPassword.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Le nouveau mot de passe est obligatoire.", Map.of(
                            "newPassword", "Le nouveau mot de passe est obligatoire."));
        }
        if (newPassword.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Le nouveau mot de passe est trop long (128 caractères au plus).", Map.of(
                            "newPassword", "Le nouveau mot de passe est trop long (128 caractères au plus)."));
        }
    }

    private KeycloakTokens currentPassword(KeycloakUser user, String password) {
        try {
            return login.passwordGrant(user.email(), password);
        } catch (PasswordChangeRequiredException reset) {
            // Staff forced a reset while this session's token was still fresh: the session is over
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED",
                    "Votre session a expiré. Reconnectez-vous.");
        } catch (ApiException refused) {
            if (refused.getStatus() == HttpStatus.UNAUTHORIZED) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CURRENT_PASSWORD",
                        "Le mot de passe actuel est incorrect.");
            }
            throw refused;
        }
    }
}
