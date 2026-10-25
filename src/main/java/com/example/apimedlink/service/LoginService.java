package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.exception.PasswordChangeRequiredException;
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
 * <p>A practitioner or staff member then needs a code from their authenticator app (sub-sprint 3,
 * MLink-62/63): the password alone opens no session. Sub-sprint 4 added the forced password change.
 */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);
    private static final String UPDATE_PASSWORD = "UPDATE_PASSWORD";
    /** The roles whose login needs the staff code. */
    private static final List<String> CODE_ROLES = List.of(Roles.PRACTITIONER, Roles.ADMINISTRATIVE_STAFF);

    private final KeycloakLoginClient keycloak;
    private final KeycloakAdminService admin;
    private final UserProvisioningService provisioning;
    private final MfaService mfa;

    public LoginService(KeycloakLoginClient keycloak, KeycloakAdminService admin,
                        UserProvisioningService provisioning, MfaService mfa) {
        this.keycloak = keycloak;
        this.admin = admin;
        this.provisioning = provisioning;
        this.mfa = mfa;
    }

    /**
     * @throws ApiException 401 {@code INVALID_CREDENTIALS}, 403 {@code ACCOUNT_DISABLED},
     *     {@code EMAIL_NOT_VERIFIED} or {@code ACCOUNT_SETUP_INCOMPLETE}, 409 {@code ACCOUNT_CONFLICT},
     *     503 {@code IDENTITY_UNAVAILABLE}
     */
    public LoginResult login(String email, String password) {
        KeycloakTokens tokens;
        try {
            tokens = keycloak.passwordGrant(email.trim(), password);
        } catch (PasswordChangeRequiredException pending) {
            // Keycloak checked the password first; its admin API says which step is missing. Only
            // UPDATE_PASSWORD alone is ours to finish: anything else would leave the account blocked
            // after the change, so it is refused before the change starts.
            KeycloakUser user = admin.findByEmail(email.trim()).orElseThrow(LoginService::invalidCredentials);
            if (user.requiredActions().equals(List.of(UPDATE_PASSWORD))) {
                return new LoginResult.PasswordChange(user.id());
            }
            log.warn("Login refused for user {}: not fully set up in Keycloak (required actions {})",
                    user.id(), user.requiredActions());
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_SETUP_INCOMPLETE",
                    "Votre compte n'est pas encore prêt. Contactez l'administration.");
        }
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
            Login login = new Login(user, token.getClaimAsString("given_name"),
                    token.getClaimAsString("family_name"), ourRoles(token), tokens);
            if (login.roles().stream().anyMatch(CODE_ROLES::contains)) {
                return new LoginResult.MfaRequired(login, !mfa.isEnrolled(user.getId()));
            }
            return new LoginResult.Success(login);
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

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "E-mail ou mot de passe incorrect, ou compte temporairement verrouillé.");
    }
}
