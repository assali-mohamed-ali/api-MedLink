package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.User;
import com.example.apimedlink.security.Roles;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * Endpoint 5 (plan.md §3 "Login"), the decisions: Keycloak checks the password, then our rules
 * decide whether a session may open. Opening it is {@code security/LoginSessions}' job.
 *
 * <p>Sub-sprint 3 adds the staff code step for practitioners and staff after the email check;
 * sub-sprint 4 the forced password change.
 */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final KeycloakLoginClient keycloak;
    private final UserProvisioningService provisioning;

    public LoginService(KeycloakLoginClient keycloak, UserProvisioningService provisioning) {
        this.keycloak = keycloak;
        this.provisioning = provisioning;
    }

    /**
     * @throws ApiException 401 {@code INVALID_CREDENTIALS}, 403 {@code ACCOUNT_DISABLED} or
     *     {@code EMAIL_NOT_VERIFIED}, 409 {@code ACCOUNT_CONFLICT}, 503 {@code IDENTITY_UNAVAILABLE}
     */
    public Login login(String email, String password) {
        KeycloakTokens tokens = keycloak.passwordGrant(email.trim(), password);
        Jwt token = tokens.accessToken();
        try {
            // Keycloak accepts an unverified email: refusing it is ours
            if (!Boolean.TRUE.equals(token.getClaimAsBoolean("email_verified"))) {
                log.info("Login refused for user {}: email not verified", token.getSubject());
                throw new ApiException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED",
                        "Confirmez votre adresse e-mail avant de vous connecter : utilisez le lien reçu par e-mail.");
            }
            // Our row for this account, created at first login for console-made accounts (GitHub #29)
            User user = provisioning.provision(
                    UUID.fromString(token.getSubject()),
                    token.getClaimAsString("email"),
                    token.getClaimAsString("preferred_username"));
            return new Login(user, token.getClaimAsString("given_name"), token.getClaimAsString("family_name"),
                    ourRoles(token), tokens);
        } catch (RuntimeException refused) {
            // Keycloak accepted the password and opened a session: no session of ours, so none of theirs
            keycloak.endSession(tokens.refreshToken());
            throw refused;
        }
    }

    /**
     * Ends the Keycloak session of a logged-out user. Best effort: a Keycloak failure is only logged.
     */
    public void logout(String refreshToken) {
        keycloak.endSession(refreshToken);
    }

    /** Only MedLink's roles; Keycloak's built-in ones (offline_access, default-roles-...) are dropped. */
    private static List<String> ourRoles(Jwt token) {
        List<String> claimed = token.getClaimAsStringList("roles");
        if (claimed == null) {
            return List.of();
        }
        return Roles.ALL.stream().filter(claimed::contains).toList();
    }
}
