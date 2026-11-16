package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.EmailToken;
import com.example.apimedlink.model.EmailToken.Purpose;
import com.example.apimedlink.model.User;
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
 * Endpoint 14 (MLink-71): the person a staff account was created for chooses their first password with the
 * mailed link. The token, the password and the verified email are one transaction: when Keycloak refuses the
 * password (policy) or fails, the token stays usable.
 *
 * <p>The link is mailed when staff create the account and is valid 24 hours. When that mail fails or the link
 * expires, {@link #resend} mails a new one, so the person is never stranded.
 */
@Service
public class AccountActivationService {

    private static final Logger log = LoggerFactory.getLogger(AccountActivationService.class);
    static final Duration RESEND_INTERVAL = Duration.ofMinutes(1);

    private final EmailTokenService tokens;
    private final KeycloakAdminService keycloak;
    private final UserRepository users;
    private final MailService mail;
    private final MailThrottle throttle;
    private final TransactionTemplate tx;

    public AccountActivationService(EmailTokenService tokens, KeycloakAdminService keycloak, UserRepository users,
                                    MailService mail, MailThrottle throttle, TransactionTemplate tx) {
        this.tokens = tokens;
        this.keycloak = keycloak;
        this.users = users;
        this.mail = mail;
        this.throttle = throttle;
        this.tx = tx;
    }

    /**
     * @throws ApiException 400 {@code INVALID_TOKEN}, 410 {@code TOKEN_EXPIRED}, 400 {@code PASSWORD_POLICY}
     */
    public void activate(String token, String password) {
        tx.executeWithoutResult(status -> {
            EmailToken used = tokens.consume(token, Purpose.ACTIVATE_ACCOUNT);
            try {
                keycloak.setPassword(used.getUserId(), password);
                keycloak.setEmailVerified(used.getUserId(), true);
            } catch (EntityNotFoundException e) {
                // the account was deleted in Keycloak since the mail was sent
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN",
                        "Ce lien n'est pas valide. Vérifiez qu'il est complet ou demandez-en un nouveau.");
            }
        });
    }

    /**
     * Mails a new activation link, at most once a minute per account; older links stop working. It does so only
     * for an account staff created (it was sent an activation link before) that is still enabled and not yet
     * activated: a patient, an unknown address, an activated or a disabled account get nothing. Says nothing about
     * the outcome: the caller always answers the same, so the address of an account cannot be discovered.
     */
    public void resend(String email) {
        Optional<UUID> userId = users.findByEmail(email.trim().toLowerCase(Locale.ROOT)).map(User::getId);
        if (userId.isEmpty()) {
            return;
        }
        UUID id = userId.get();
        Instant last = tokens.lastIssuedAt(id, Purpose.ACTIVATE_ACCOUNT);
        if (last == null) {
            return; // not created by staff: nothing to activate
        }
        if (Instant.now().isBefore(last.plus(RESEND_INTERVAL))) {
            return;
        }
        // The check above reads, then we write: only one of several requests at once gets past here (GitHub #51)
        if (!throttle.allow("activation-resend", id.toString(), RESEND_INTERVAL)) {
            return;
        }
        KeycloakUser account;
        try {
            account = keycloak.getUser(id);
        } catch (RuntimeException e) {
            log.warn("Activation mail not resent: Keycloak lookup failed ({})", e.getClass().getSimpleName());
            return;
        }
        if (!account.enabled() || account.emailVerified()) {
            return;
        }
        String token = tx.execute(status -> {
            tokens.cancelUnused(id, Purpose.ACTIVATE_ACCOUNT);
            return tokens.issue(id, Purpose.ACTIVATE_ACCOUNT);
        });
        sendActivationMail(account.email(), account.firstName(), token);
    }

    /** A mail failure is only logged: the person can ask for a new link. */
    private void sendActivationMail(String email, String firstName, String token) {
        try {
            mail.sendAccountActivation(email, firstName, token);
        } catch (MailException e) {
            log.warn("Activation mail could not be sent ({})", e.getClass().getSimpleName());
        }
    }
}
