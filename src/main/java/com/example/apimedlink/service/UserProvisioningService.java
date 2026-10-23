package com.example.apimedlink.service;

<<<<<<< HEAD
=======
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.UserRepository;
>>>>>>> 7dbf59d (feat: audit login and account changes)
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
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final AuditService audit;

<<<<<<< HEAD
    public UserProvisioningService(UserRepository users,
            TransactionTemplate tx,
            AuditService audit) {
=======
    public UserProvisioningService(UserRepository users, AuditService audit, TransactionTemplate tx) {
>>>>>>> 7dbf59d (feat: audit login and account changes)
        this.users = users;
        this.audit = audit;
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
<<<<<<< HEAD
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
                    audit.record(
                            AuditAction.ACCOUNT_UPDATED,
                            "USER",
                            id.toString(),
                            AuditOutcome.SUCCESS);
                }
                return result;
            });
=======
        String normalizedUsername = username == null || username.isBlank() ? normalizedEmail : username.trim();
        Provisioned provisioned;
        try {
            provisioned = tx.execute(status -> users.findById(id)
                    .map(existing -> follow(existing, normalizedEmail, normalizedUsername))
                    .orElseGet(() -> new Provisioned(create(id, normalizedEmail, normalizedUsername), false)));
>>>>>>> 7dbf59d (feat: audit login and account changes)
        } catch (DataIntegrityViolationException raced) {
            // Two first logins at once: the primary key let the other one insert. Read its
            // row.
            return users.findById(id).orElseThrow(() -> conflict(id));
        }
        if (provisioned.accountUpdated()) {
            audit.record(AuditAction.ACCOUNT_UPDATED, "USER", id.toString(), AuditOutcome.SUCCESS);
        }
        return provisioned.user();
    }

    private User create(UUID id, String email, String username) {
        if (users.existsByEmail(email) || users.existsByUsername(username)) {
            return users.findById(id).orElseThrow(() -> conflict(id));
        }
        return users.saveAndFlush(User.builder().id(id).username(username).email(email).build());
    }

<<<<<<< HEAD
    /**
     * Keycloak is the source of truth for the email and the username: follow their
     * changes.
     */
    private User follow(User existing, String email, String username) {
        boolean updated = false;

=======
    /** Keycloak is the source of truth for the email and the username: follow their changes. */
    private Provisioned follow(User existing, String email, String username) {
        boolean updated = false;
>>>>>>> 7dbf59d (feat: audit login and account changes)
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
<<<<<<< HEAD
        }

        if (updated) {
            existing.markUpdated();
=======
>>>>>>> 7dbf59d (feat: audit login and account changes)
        }
        return new Provisioned(existing, updated);
    }

    private static ApiException conflict(UUID id) {
        log.warn("Login refused for Keycloak user {}: their email or username belongs to another account", id);
        return new ApiException(HttpStatus.CONFLICT, "ACCOUNT_CONFLICT",
                "Ce compte ne peut pas être ouvert : son adresse e-mail est déjà liée à un autre compte. "
                        + "Contactez l'administration.");
    }
<<<<<<< HEAD
}
=======

    private record Provisioned(User user, boolean accountUpdated) {}
}
>>>>>>> 7dbf59d (feat: audit login and account changes)
