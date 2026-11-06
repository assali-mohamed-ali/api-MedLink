package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.exception.EmailTakenException;
import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.UserRepository;
import com.example.apimedlink.security.Roles;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Account management by staff (MLink-69): list, create a practitioner or staff account, change a role.
 * The Keycloak user and our users row are created in the same request; when ours fails, the Keycloak
 * user is deleted, so no account is left half made. Patients are never created here: they register.
 */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    private final KeycloakAdminService keycloak;
    private final UserRepository users;
    private final TransactionTemplate tx;

    public AdminUserService(KeycloakAdminService keycloak, UserRepository users, TransactionTemplate tx) {
        this.keycloak = keycloak;
        this.users = users;
        this.tx = tx;
    }

    public KeycloakUserPage list(String search, int page, int size) {
        return keycloak.searchUsers(search, page, size);
    }

        /** @throws ApiException 409 {@code EMAIL_TAKEN} when the email already has an account */
    public KeycloakUserPage.Entry create(String email, String firstName, String lastName, String role) {
        String groupPath = staffGroupOf(role);
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(normalized) || users.existsByUsername(normalized)) {
            throw emailTaken();
        }
        UUID id;
        try {
            // Outside any transaction: no database connection held while Keycloak answers
            id = keycloak.createUser(normalized, normalized, firstName, lastName, groupPath);
        } catch (EmailTakenException knownToKeycloakOnly) {
            throw emailTaken();
        }
        try {
            tx.executeWithoutResult(status ->
                    users.save(User.builder().id(id).username(normalized).email(normalized).build()));
        } catch (RuntimeException e) {
            deleteKeycloakUser(id);
            throw e;
        }
        return new KeycloakUserPage.Entry(
                new KeycloakUser(id, normalized, firstName, lastName, true, false), groupPath);
    }

        /** Staff may learn that an email is taken (unlike a visitor signing up). */
    private static ApiException emailTaken() {
        return new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN",
                "Un compte existe déjà avec cette adresse e-mail.");
    }

    /** @throws jakarta.persistence.EntityNotFoundException when the user does not exist (404) */
    public void changeRole(UUID id, String role) {
        keycloak.moveToGroup(id, staffGroupOf(role));
    }

    /** The Keycloak group of a staff role; PATIENT and anything unknown are refused. */
    private static String staffGroupOf(String role) {
        String groupPath = RoleGroups.pathOf(role);
        if (groupPath == null || Roles.PATIENT.equals(role)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Le rôle doit être PRACTITIONER ou ADMINISTRATIVE_STAFF.");
        }
        return groupPath;
    }

    private void deleteKeycloakUser(UUID id) {
        try {
            keycloak.deleteUser(id);
        } catch (RuntimeException e) {
            log.error("Account creation failed and Keycloak user {} could not be deleted ({}): delete it by hand",
                    id, e.getClass().getSimpleName());
        }
    }
}
