package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Endpoint 10: the current password is checked by Keycloak (password grant), then the new one is
 * set through the admin API. The password grant's Keycloak session is ended at once.
 */
@Service
public class PasswordService {

    private final KeycloakAdminService keycloak;
    private final KeycloakLoginClient login;

    public PasswordService(KeycloakAdminService keycloak, KeycloakLoginClient login) {
        this.keycloak = keycloak;
        this.login = login;
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
        } finally {
            login.endSession(tokens.refreshToken());
        }
    }

    /** The forced branch: the pending login already proved the current password. */
    public void changeForced(UUID userId, String newPassword) {
        validateNewPassword(newPassword);
        keycloak.setPassword(userId, newPassword);
        keycloak.clearPasswordChange(userId);
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
        } catch (ApiException refused) {
            if (refused.getStatus() == HttpStatus.UNAUTHORIZED) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CURRENT_PASSWORD",
                        "Le mot de passe actuel est incorrect.");
            }
            throw refused;
        }
    }
}
