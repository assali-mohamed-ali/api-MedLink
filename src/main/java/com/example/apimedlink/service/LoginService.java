package com.example.apimedlink.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.exception.PasswordChangeRequiredException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.User;
import com.example.apimedlink.security.Roles;

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
    private final AuditService audit;

    public LoginService(KeycloakLoginClient keycloak, KeycloakAdminService admin,
                        UserProvisioningService provisioning, MfaService mfa, AuditService audit) {
        this.keycloak = keycloak;
        this.admin = admin;
        this.provisioning = provisioning;
        this.mfa = mfa;
        this.audit = audit;
    }

    /**
     * Checks the credentials and our rules. It does not audit a success: the caller records it with
     * {@link #recordLoginSuccess} once the session really exists.
     */
    public LoginResult login(String email, String password) {
        KeycloakTokens tokens = null;

        try {
            tokens = keycloak.passwordGrant(email.trim(), password);
        } catch (PasswordChangeRequiredException pending) {
            KeycloakUser user = admin.findByEmail(email.trim()).orElseThrow(LoginService::invalidCredentials);
            if (user.requiredActions().equals(List.of(UPDATE_PASSWORD))) {
                // Password is right, a change is required: the caller starts it and records the challenge.
                return new LoginResult.PasswordChange(user.id());
            }
            log.warn("Login refused for user {}: not fully set up in Keycloak (required actions {})",
                    user.id(), user.requiredActions());
            recordLogin(user.id(), AuditOutcome.FAILURE);
            throw setupIncomplete();
        } catch (RuntimeException refused) {
            // Utilisateur non identifié (mauvais mdp / inconnu) → UNKNOWN, userId null
            auditLoginFailureUnknown();
            throw refused;
        }

        Jwt token = tokens.accessToken();
        String subject = token.getSubject();
        UUID subjectId = UUID.fromString(subject);

        try {
            if (!Boolean.TRUE.equals(token.getClaimAsBoolean("email_verified"))) {
                log.info("Login refused for user {}: email not verified", subject);
                throw new ApiException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED",
                        "Confirmez votre adresse e-mail avant de vous connecter : utilisez le lien reçu par e-mail.");
            }
            // One person, one role: an account left in two MedLink groups gets no session rather than a mixed one
            List<String> roles = ourRoles(token);
            if (roles.size() > 1) {
                log.warn("Login refused for user {}: holds more than one role {}", token.getSubject(), roles);
                throw setupIncomplete();
            }
            // Our row for this account, created at first login for console-made accounts (GitHub #29)
            User user = provisioning.provision(
                    subjectId,
                    token.getClaimAsString("email"),
                    token.getClaimAsString("preferred_username"));
            Login login = new Login(user, token.getClaimAsString("given_name"),
                    token.getClaimAsString("family_name"), roles, tokens);
            if (login.roles().stream().anyMatch(CODE_ROLES::contains)) {
                return new LoginResult.MfaRequired(login, !mfa.isEnrolled(user.getId()));
            }
            return new LoginResult.Success(login);
        } catch (RuntimeException refused) {
            // Utilisateur identifié (token OK) mais login refusé après
            recordLogin(subjectId, AuditOutcome.FAILURE);

            try {
                keycloak.endSession(tokens.refreshToken());
            } catch (Exception endEx) {
                log.warn("Failed to end Keycloak session after refused login for subject={}", subject, endEx);
            }

            throw refused;
        }
    }

    /** The session exists: only now is the login a success. */
    public void recordLoginSuccess(UUID userId) {
        recordLogin(userId, AuditOutcome.SUCCESS);
    }

    /** The credentials were right but the session (or the pending step) could not be established. */
    public void recordLoginFailure(UUID userId) {
        recordLogin(userId, AuditOutcome.FAILURE);
    }

    /**
     * The password is right but must be changed first. This is a challenge, not an authenticated login:
     * no session exists until the change is done.
     */
    public void recordPasswordChangeRequired(UUID userId) {
        try {
            audit.record(AuditAction.PASSWORD_CHANGE_REQUIRED, "USER", userId.toString(),
                    AuditOutcome.SUCCESS, userId);
        } catch (Exception auditEx) {
            log.warn("Failed to audit PASSWORD_CHANGE_REQUIRED for {}", userId, auditEx);
        }
    }

    private void recordLogin(UUID userId, AuditOutcome outcome) {
        try {
            audit.record(AuditAction.LOGIN, "USER", userId.toString(), outcome, userId);
        } catch (Exception auditEx) {
            log.warn("Failed to audit LOGIN {} for {}", outcome, userId, auditEx);
        }
    }

    /** Échec sans identité connue : resource_id=UNKNOWN, user_id=null. */
    private void auditLoginFailureUnknown() {
        try {
            audit.record(AuditAction.LOGIN, "USER", "UNKNOWN", AuditOutcome.FAILURE, null);
        } catch (Exception auditEx) {
            log.warn("Failed to audit LOGIN FAILURE (unknown user)", auditEx);
        }
    }

    public void logout(String refreshToken) {
        keycloak.endSession(refreshToken);
    }

    private static List<String> ourRoles(Jwt token) {
        List<String> claimed = token.getClaimAsStringList("roles");
        if (claimed == null) {
            return List.of();
        }
        return Roles.ALL.stream().filter(claimed::contains).toList();
    }

    private static ApiException setupIncomplete() {
        return new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_SETUP_INCOMPLETE",
                "Votre compte n'est pas encore prêt. Contactez l'administration.");
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "E-mail ou mot de passe incorrect, ou compte temporairement verrouillé.");
    }
}
