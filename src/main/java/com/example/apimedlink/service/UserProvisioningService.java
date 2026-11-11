package com.example.apimedlink.service;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.UserRepository;

/**
 * Exactly one {@code users} row per Keycloak account, created at first login
 * for accounts made
 * outside the API (Keycloak console, realm import). Called by every login path.
 * GitHub issue #29.
 */
@Service
public class UserProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(UserProvisioningService.class);

    private final UserRepository users;
    private final TransactionTemplate tx;
    private final AuditService audit;

    public UserProvisioningService(UserRepository users,
            TransactionTemplate tx,
            AuditService audit) {
        this.users = users;
        this.tx = tx;
        this.audit = audit;
    }

    /**
     * Returns the row of this Keycloak account, creating it if needed and following
     * an email or
     * username changed in Keycloak.
     *
     * @throws ApiException 409 {@code ACCOUNT_CONFLICT} when the email belongs to
     *                      another account
     *                      (an account recreated in Keycloak): nothing is
     *                      overwritten; 409 {@code ACCOUNT_INCOMPLETE}
     *                      when the account has no email
     */
    public User provision(UUID id, String email, String username) {
        if (email == null || email.isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_INCOMPLETE",
                    "Ce compte n'a pas d'adresse e-mail. Contactez l'administration.");
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        String normalizedUsername = username == null || username.isBlank()
                ? normalizedEmail
                : username.trim();

        try {
            // TOUT (update + audit) dans la même transaction
            return tx.execute(status -> {
                User result = users.findById(id)
                        .map(existing -> follow(existing, normalizedEmail, normalizedUsername))
                        .orElseGet(() -> create(id, normalizedEmail, normalizedUsername));

                if (result.wasUpdated()) {
                    audit.recordInCurrentTransaction(
                            AuditAction.ACCOUNT_UPDATED,
                            "USER",
                            id.toString(),
                            AuditOutcome.SUCCESS);
                }
                return result;
            });
        } catch (InsertRaced raced) {
            // Two first logins at once: the other one inserted the row first. Only a lost insert is
            // read back; any other integrity failure (the audit entry, the commit) is thrown.
            return users.findById(id).orElseThrow(() -> conflict(id));
        }
    }

    private User create(UUID id, String email, String username) {
        if (users.existsByEmail(email) || users.existsByUsername(username)) {
            return users.findById(id).orElseThrow(() -> conflict(id));
        }
        try {
            return users.saveAndFlush(User.builder().id(id).username(username).email(email).build());
        } catch (DataIntegrityViolationException lostTheRace) {
            throw new InsertRaced(lostTheRace);
        }
    }

    /**
     * Keycloak is the source of truth for the email and the username: follow their
     * changes.
     */
    private User follow(User existing, String email, String username) {
        boolean updated = false;

        if (!existing.getEmail().equals(email)) {
            Optional<User> holder = users.findByEmail(email);
            if (holder.isPresent()) {
                throw conflict(existing.getId());
            }
            existing.setEmail(email);
            updated = true;
        }

        if (!existing.getUsername().equals(username) && !users.existsByUsername(username)) {
            existing.setUsername(username);
            updated = true;
        }

        if (updated) {
            existing.markUpdated();
        }
        return existing;
    }

    private static ApiException conflict(UUID id) {
        log.warn("Login refused for Keycloak user {}: their email or username belongs to another account", id);
        return new ApiException(HttpStatus.CONFLICT, "ACCOUNT_CONFLICT",
                "Ce compte ne peut pas être ouvert : son adresse e-mail est déjà liée à un autre compte. "
                        + "Contactez l'administration.");
    }

    /** The insert of a first login lost its race: the row now exists. Only this failure is read back. */
    private static final class InsertRaced extends RuntimeException {
        InsertRaced(Throwable cause) {
            super(null, cause, false, false);
        }
    }
}
