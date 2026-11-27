package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.exception.InvalidPasswordException;
import com.example.apimedlink.exception.KeycloakUnavailableException;
import com.example.apimedlink.model.EmailToken;
import com.example.apimedlink.model.EmailToken.Purpose;
import com.example.apimedlink.repository.SessionRevocationStore;
import com.example.apimedlink.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * US-03: "forgot my password" with our own mailed link (the browser never sees Keycloak). {@link #request} says
 * nothing about the outcome: the caller answers the same whether or not an account exists, and mails only an enabled,
 * verified account, at most once a minute. {@link #reset} uses the single-use token, sets the new password in Keycloak
 * and ends every session of the account (ours first, as a password change does). A password Keycloak refuses leaves
 * the token usable. Never logs a token, a password or an address.
 */
@Service
public class ForgotPasswordService {

    private static final Logger log = LoggerFactory.getLogger(ForgotPasswordService.class);
    static final Duration REQUEST_INTERVAL = Duration.ofMinutes(1);

    private final EmailTokenService tokens;
    private final KeycloakAdminService keycloak;
    private final UserRepository users;
    private final MailService mail;
    private final MailThrottle throttle;
    private final SessionRevocationStore revocations;
    private final TransactionTemplate tx;

    public ForgotPasswordService(EmailTokenService tokens, KeycloakAdminService keycloak, UserRepository users,
            MailService mail, MailThrottle throttle, SessionRevocationStore revocations, TransactionTemplate tx) {
        this.tokens = tokens;
        this.keycloak = keycloak;
        this.users = users;
        this.mail = mail;
        this.throttle = throttle;
        this.revocations = revocations;
        this.tx = tx;
    }

    public void request(String email) {
        Optional<UUID> found = users.findByEmail(email.trim().toLowerCase(Locale.ROOT)).map(user -> user.getId());
        if (found.isEmpty()) {
            return;
        }
        UUID id = found.get();
        if (!throttle.allow("password-forgot", id.toString(), REQUEST_INTERVAL)) {
            return;
        }
        KeycloakUser account;
        try {
            account = keycloak.getUser(id);
        } catch (RuntimeException e) {
            log.warn("Password reset mail not sent: Keycloak lookup failed ({})", e.getClass().getSimpleName());
            return;
        }
        if (!account.enabled() || !account.emailVerified()) {
            return;
        }
        String token = tx.execute(status -> {
            tokens.cancelUnused(id, Purpose.PASSWORD_RESET);
            return tokens.issue(id, Purpose.PASSWORD_RESET);
        });
        try {
            mail.sendPasswordReset(account.email(), account.firstName(), token);
        } catch (MailException e) {
            log.warn("Password reset mail could not be sent ({})", e.getClass().getSimpleName());
        }
    }

    public void reset(String token, String newPassword) {
        UUID userId = tx.execute(status -> {
            EmailToken used = tokens.consume(token, Purpose.PASSWORD_RESET);
            setPasswordClosingOldSessions(used.getUserId(), newPassword);
            return used.getUserId();
        });
        try {
            keycloak.endSessions(userId);
        } catch (KeycloakUnavailableException e) {
            log.warn("Password reset for user {}: its Keycloak sessions could not be ended, they end at Keycloak's "
                    + "idle timeout", userId);
        }
    }

    /** As a password change: the old sessions are refused first; a password Keycloak refuses puts the mark back. */
    private void setPasswordClosingOldSessions(UUID userId, String newPassword) {
        Optional<Instant> previous = revocations.revokedAt(userId);
        Instant mark = revocations.revoke(userId);
        try {
            keycloak.setPassword(userId, newPassword);
        } catch (InvalidPasswordException refused) {
            revocations.undo(userId, mark, previous);
            throw refused;
        } catch (EntityNotFoundException gone) {
            // the account was deleted in Keycloak since the mail was sent
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN",
                    "Ce lien n'est pas valide. Vérifiez qu'il est complet ou demandez-en un nouveau.");
        }
    }
}
