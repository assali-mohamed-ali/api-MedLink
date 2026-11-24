package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.EmailToken;
import com.example.apimedlink.model.EmailToken.Purpose;
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

/** Email verification with our own tokens and mails: Keycloak never sends anything. */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    static final Duration RESEND_INTERVAL = Duration.ofMinutes(1);

    private final EmailTokenService tokens;
    private final KeycloakAdminService keycloak;
    private final UserRepository users;
    private final MailService mail;
    private final MailThrottle throttle;
    private final TransactionTemplate tx;

    public EmailVerificationService(
            EmailTokenService tokens,
            KeycloakAdminService keycloak,
            UserRepository users,
            MailService mail,
            MailThrottle throttle,
            TransactionTemplate tx) {
        this.tokens = tokens;
        this.keycloak = keycloak;
        this.users = users;
        this.mail = mail;
        this.throttle = throttle;
        this.tx = tx;
    }

    /**
     * Uses the token and marks the email verified in Keycloak, in one transaction: if Keycloak
     * fails, the token stays usable.
     */
    public void verify(String token) {
        tx.executeWithoutResult(status -> {
            EmailToken used = tokens.consume(token, Purpose.VERIFY_EMAIL);
            try {
                keycloak.setEmailVerified(used.getUserId(), true);
            } catch (EntityNotFoundException e) {
                // the account was deleted in Keycloak since the mail was sent
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN",
                        "Ce lien n'est pas valide. Vérifiez qu'il est complet ou demandez-en un nouveau.");
            }
        });
    }

    /**
     * Mails a new link only to an existing, unverified account, at most once a minute; older
     * links stop working. Says nothing about the outcome: the caller always answers the same.
     */
    public void resend(String email) {
        Optional<UUID> userId = users.findByEmail(email.trim().toLowerCase(Locale.ROOT)).map(u -> u.getId());
        if (userId.isEmpty()) {
            return;
        }
        UUID id = userId.get();
        Instant last = tokens.lastIssuedAt(id, Purpose.VERIFY_EMAIL);
        if (last != null && Instant.now().isBefore(last.plus(RESEND_INTERVAL))) {
            return;
        }
        // The check above reads, then we write: only one of several requests at once gets past here (GitHub #51)
        if (!throttle.allow("verification-resend", id.toString(), RESEND_INTERVAL)) {
            return;
        }
        KeycloakUser account;
        try {
            account = keycloak.getUser(id);
        } catch (RuntimeException e) {
            log.warn("Verification mail not resent: Keycloak lookup failed ({})", e.getClass().getSimpleName());
            return;
        }
        if (account.emailVerified()) {
            return;
        }
        String token = tx.execute(status -> {
            tokens.cancelUnused(id, Purpose.VERIFY_EMAIL);
            return tokens.issue(id, Purpose.VERIFY_EMAIL);
        });
        sendVerificationMail(account.email(), account.firstName(), token);
    }

    /** A mail failure is only logged: the user can ask for a new link. */
    void sendVerificationMail(String email, String firstName, String token) {
        try {
            mail.sendEmailVerification(email, firstName, token);
        } catch (MailException e) {
            log.warn("Verification mail could not be sent ({})", e.getClass().getSimpleName());
        }
    }
}
