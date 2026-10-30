package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.exception.InvalidPasswordException;
import com.example.apimedlink.exception.KeycloakUnavailableException;
import com.example.apimedlink.exception.PasswordChangeRequiredException;
import com.example.apimedlink.repository.SessionRevocationStore;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Endpoint 10: the current password is checked by Keycloak (password grant), then the new one is
 * set through the admin API. A change ends every session of the user: the API refuses the old ones
 * itself (session_revocations), whatever Keycloak does, and Keycloak's are signed out too.
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
            setPasswordClosingOldSessions(userId, newPassword);
        } catch (RuntimeException failed) {
            login.endSession(tokens.refreshToken());
            throw failed;
        }
        endSessions(userId);
    }

    /**
     * The old sessions are refused before the password changes, so none outlives the old password, even
     * if Keycloak fails afterwards. A password Keycloak refused puts the mark back; no answer keeps it,
     * since the password may have changed (fail closed: the user logs in again).
     */
    private void setPasswordClosingOldSessions(UUID userId, String newPassword) {
        Optional<Instant> previous = revocations.revokedAt(userId);
        Instant mark = revocations.revoke(userId);
        try {
            keycloak.setPassword(userId, newPassword);
        } catch (InvalidPasswordException refused) {
            revocations.undo(userId, mark, previous);
            throw refused;
        }
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
     * The API already refuses the old sessions and the browser never holds Keycloak's tokens, so a
     * failure here leaves only unusable Keycloak sessions until their idle timeout: logged, not undone.
     */
    private void endSessions(UUID userId) {
        try {
            keycloak.endSessions(userId);
        } catch (KeycloakUnavailableException e) {
            log.warn("Password changed for user {}: its Keycloak sessions could not be ended, "
                    + "they end at Keycloak's idle timeout", userId);
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
