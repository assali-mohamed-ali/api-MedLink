package com.example.apimedlink.security;

import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Who is calling (MLink-58): the caller's id is the Keycloak id ({@code sub}) the login saved in the
 * session, which is also {@code users.id}. Every "own data only" rule starts here.
 */
@Component
public class CurrentUser {

    private static final Logger log = LoggerFactory.getLogger(CurrentUser.class);

    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    /** The caller's id (Keycloak id = users.id). */
    public UUID id() {
        return UUID.fromString(authentication().getName());
    }

    /**
     * The caller's id, or null for an anonymous caller (the audit trail records refusals of both). Never
     * throws: an identity that is not a Keycloak id is logged and treated as unknown, so an audit event is
     * never lost (review on PR #56).
     */
    public UUID idOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException notAKeycloakId) {
            // Never the name itself in the log: it could be an email
            log.warn("A caller's identity is not a Keycloak id: recorded without a user id");
            return null;
        }
    }

    /** The caller's row; the login creates it, so it exists for any logged-in user. */
    public User user() {
        UUID id = id();
        return users.findById(id).orElseThrow(() -> new EntityNotFoundException("User " + id + " not found"));
    }

    public boolean hasRole(String role) {
        return authentication().getAuthorities().stream()
                .anyMatch(authority -> ("ROLE_" + role).equals(authority.getAuthority()));
    }

    private static Authentication authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            throw new AuthenticationCredentialsNotFoundException("No logged-in user");
        }
        return authentication;
    }
}
