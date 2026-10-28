package com.example.apimedlink.service;

<<<<<<< HEAD
<<<<<<< HEAD
=======
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.exception.PasswordChangeRequiredException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.User;
import com.example.apimedlink.security.Roles;
>>>>>>> 7dbf59d (feat: audit login and account changes)
=======
>>>>>>> 06ee374 (style: ensure final newlines for end-of-file-fixer)
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

<<<<<<< HEAD
/**
 * Endpoint 5 (plan.md §3 "Login"), the decisions: Keycloak checks the password,
 * then our rules
 * decide whether a session may open. Opening it is
 * {@code security/LoginSessions}' job.
 *
 * <p>
 * Sub-sprint 3 adds the staff code step for practitioners and staff after the
 * email check;
 * sub-sprint 4 the forced password change.
 */
=======
>>>>>>> 06ee374 (style: ensure final newlines for end-of-file-fixer)
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);
    private static final String UPDATE_PASSWORD = "UPDATE_PASSWORD";

    private final KeycloakLoginClient keycloak;
    private final KeycloakAdminService admin;
    private final UserProvisioningService provisioning;
    private final AuditService audit;

<<<<<<< HEAD
<<<<<<< HEAD
=======
>>>>>>> 06ee374 (style: ensure final newlines for end-of-file-fixer)
    public LoginService(KeycloakLoginClient keycloak,
            KeycloakAdminService admin,
            UserProvisioningService provisioning,
            AuditService audit) {
<<<<<<< HEAD
=======
    public LoginService(KeycloakLoginClient keycloak, KeycloakAdminService admin,
                        UserProvisioningService provisioning, AuditService audit) {
>>>>>>> 7dbf59d (feat: audit login and account changes)
=======
>>>>>>> 06ee374 (style: ensure final newlines for end-of-file-fixer)
        this.keycloak = keycloak;
        this.admin = admin;
        this.provisioning = provisioning;
        this.audit = audit;
    }

<<<<<<< HEAD
    /**
     * @throws ApiException 401 {@code INVALID_CREDENTIALS}, 403
     *                      {@code ACCOUNT_DISABLED},
     *                      {@code EMAIL_NOT_VERIFIED} or
     *                      {@code ACCOUNT_SETUP_INCOMPLETE}, 409
     *                      {@code ACCOUNT_CONFLICT},
     *                      503 {@code IDENTITY_UNAVAILABLE}
     */
=======
>>>>>>> 06ee374 (style: ensure final newlines for end-of-file-fixer)
    public LoginResult login(String email, String password) {
        KeycloakTokens tokens = null;

        try {
            tokens = keycloak.passwordGrant(email.trim(), password);
        } catch (PasswordChangeRequiredException pending) {
<<<<<<< HEAD
            // Keycloak checked the password first; its admin API says which step is
            // missing.
=======
>>>>>>> 06ee374 (style: ensure final newlines for end-of-file-fixer)
            KeycloakUser user = admin.findByEmail(email.trim()).orElseThrow(LoginService::invalidCredentials);
            if (user.requiredActions().equals(List.of(UPDATE_PASSWORD))) {
                // Mot de passe OK, changement requis — utilisateur identifié
                try {
                    audit.record(AuditAction.LOGIN, "USER", user.id().toString(),
                            AuditOutcome.SUCCESS, user.id());
                } catch (Exception auditEx) {
                    log.warn("Failed to audit LOGIN SUCCESS (password-change) for {}", user.id(), auditEx);
                }
                return new LoginResult.PasswordChange(user.id());
            }
            log.warn("Login refused for user {}: not fully set up in Keycloak (required actions {})",
                    user.id(), user.requiredActions());
<<<<<<< HEAD
<<<<<<< HEAD
            auditLoginFailure(); // refus → FAILURE / UNKNOWN
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_SETUP_INCOMPLETE",
                    "Votre compte n'est pas encore prêt. Contactez l'administration.");
        } catch (RuntimeException refused) {
            // Mauvais mot de passe / user inconnu / etc. → jamais l'email dans resource_id
            auditLoginFailure();
=======
            audit.record(AuditAction.LOGIN, "USER", user.id().toString(), AuditOutcome.FAILURE);
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_SETUP_INCOMPLETE",
                    "Votre compte n'est pas encore prêt. Contactez l'administration.");
        } catch (ApiException refused) {
            audit.record(AuditAction.LOGIN, "USER", UNKNOWN_LOGIN_USER, AuditOutcome.FAILURE);
>>>>>>> 7dbf59d (feat: audit login and account changes)
=======
            try {
                audit.record(AuditAction.LOGIN, "USER", user.id().toString(),
                        AuditOutcome.FAILURE, user.id());
            } catch (Exception auditEx) {
                log.warn("Failed to audit LOGIN FAILURE (setup incomplete) for {}", user.id(), auditEx);
            }
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_SETUP_INCOMPLETE",
                    "Votre compte n'est pas encore prêt. Contactez l'administration.");
        } catch (RuntimeException refused) {
            // Utilisateur non identifié (mauvais mdp / inconnu) → UNKNOWN, userId null
            auditLoginFailureUnknown();
>>>>>>> 06ee374 (style: ensure final newlines for end-of-file-fixer)
            throw refused;
        }

        Jwt token = tokens.accessToken();
<<<<<<< HEAD
<<<<<<< HEAD
        String subject = token.getSubject();

=======
        User user;
>>>>>>> 7dbf59d (feat: audit login and account changes)
=======
        String subject = token.getSubject();
        UUID subjectId = UUID.fromString(subject);

>>>>>>> 06ee374 (style: ensure final newlines for end-of-file-fixer)
        try {
            if (!Boolean.TRUE.equals(token.getClaimAsBoolean("email_verified"))) {
                log.info("Login refused for user {}: email not verified", subject);
                throw new ApiException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED",
                        "Confirmez votre adresse e-mail avant de vous connecter : utilisez le lien reçu par e-mail.");
            }
<<<<<<< HEAD
<<<<<<< HEAD

            // Our row for this account, created at first login for console-made accounts
            // (GitHub #29)
            User user = provisioning.provision(
                    UUID.fromString(subject),
                    token.getClaimAsString("email"),
                    token.getClaimAsString("preferred_username"));

            // Login complet réussi (patient / pas de MFA ici) — audit best-effort
            try {
                audit.record(AuditAction.LOGIN, "USER", subject, AuditOutcome.SUCCESS);
            } catch (Exception auditEx) {
                log.warn("Failed to audit LOGIN SUCCESS for subject={}", subject, auditEx);
            }

            return new LoginResult.Success(new Login(
                    user,
                    token.getClaimAsString("given_name"),
                    token.getClaimAsString("family_name"),
                    ourRoles(token),
                    tokens));

        } catch (RuntimeException refused) {
            // Audit FAILURE best-effort (resource_id = UNKNOWN, jamais l'email)
            auditLoginFailure();

            // Toujours tenter de fermer la session Keycloak, même si l'audit a planté
            try {
                keycloak.endSession(tokens.refreshToken());
            } catch (Exception endEx) {
                log.warn("Failed to end Keycloak session after refused login for subject={}", subject, endEx);
            }

=======
            // Our row for this account, created at first login for console-made accounts (GitHub #29)
            user = provisioning.provision(
                    UUID.fromString(token.getSubject()),
=======

            User user = provisioning.provision(
                    subjectId,
>>>>>>> 06ee374 (style: ensure final newlines for end-of-file-fixer)
                    token.getClaimAsString("email"),
                    token.getClaimAsString("preferred_username"));

            try {
                audit.record(AuditAction.LOGIN, "USER", user.getId().toString(),
                        AuditOutcome.SUCCESS, user.getId());
            } catch (Exception auditEx) {
                log.warn("Failed to audit LOGIN SUCCESS for subject={}", subject, auditEx);
            }

            return new LoginResult.Success(new Login(
                    user,
                    token.getClaimAsString("given_name"),
                    token.getClaimAsString("family_name"),
                    ourRoles(token),
                    tokens));

        } catch (RuntimeException refused) {
<<<<<<< HEAD
            // Keycloak accepted the password and opened a session: no session of ours, so none of theirs
            audit.record(AuditAction.LOGIN, "USER", token.getSubject(), AuditOutcome.FAILURE);
            keycloak.endSession(tokens.refreshToken());
>>>>>>> 7dbf59d (feat: audit login and account changes)
=======
            // Utilisateur identifié (token OK) mais login refusé après
            try {
                audit.record(AuditAction.LOGIN, "USER", subject,
                        AuditOutcome.FAILURE, subjectId);
            } catch (Exception auditEx) {
                log.warn("Failed to audit LOGIN FAILURE for subject={}", subject, auditEx);
            }

            try {
                keycloak.endSession(tokens.refreshToken());
            } catch (Exception endEx) {
                log.warn("Failed to end Keycloak session after refused login for subject={}", subject, endEx);
            }

>>>>>>> 06ee374 (style: ensure final newlines for end-of-file-fixer)
            throw refused;
        }
    }

<<<<<<< HEAD
    /** Audit LOGIN FAILURE avec resource_id=UNKNOWN (best-effort). */
    private void auditLoginFailure() {
        try {
            audit.record(AuditAction.LOGIN, "USER", "UNKNOWN", AuditOutcome.FAILURE);
        } catch (Exception auditEx) {
            log.warn("Failed to audit LOGIN FAILURE", auditEx);
        }
    }

    /**
     * Ends the Keycloak session of a logged-out user. Best effort: a Keycloak
     * failure is only logged.
     */
=======
    /** Échec sans identité connue : resource_id=UNKNOWN, user_id=null. */
    private void auditLoginFailureUnknown() {
        try {
            audit.record(AuditAction.LOGIN, "USER", "UNKNOWN", AuditOutcome.FAILURE, null);
        } catch (Exception auditEx) {
            log.warn("Failed to audit LOGIN FAILURE (unknown user)", auditEx);
        }
    }

>>>>>>> 06ee374 (style: ensure final newlines for end-of-file-fixer)
    public void logout(String refreshToken) {
        keycloak.endSession(refreshToken);
    }

<<<<<<< HEAD
    /**
     * Only MedLink's roles; Keycloak's built-in ones (offline_access,
     * default-roles-...) are dropped.
     */
=======
>>>>>>> 06ee374 (style: ensure final newlines for end-of-file-fixer)
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