package com.example.apimedlink.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * A login that Keycloak accepted but that still has UPDATE_PASSWORD pending. The session holds only
 * the user id, for five minutes, and never a full authentication: other endpoints stay closed.
 */
@Component
public class PasswordChangePending {

    public static final String ATTRIBUTE = PasswordChangePending.class.getName();
    private static final Duration TTL = Duration.ofMinutes(5);

    public void start(UUID userId, HttpServletRequest request) {
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(true);
        session.setAttribute(ATTRIBUTE, new Pending(userId, Instant.now().plus(TTL)));
        session.setMaxInactiveInterval((int) TTL.toSeconds());
    }

    public Optional<UUID> userId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        Pending pending = (Pending) session.getAttribute(ATTRIBUTE);
        if (pending == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(pending.expiresAt())) {
            session.invalidate();
            return Optional.empty();
        }
        return Optional.of(pending.userId());
    }

    public void clear(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(ATTRIBUTE);
            session.invalidate();
        }
    }

    record Pending(UUID userId, Instant expiresAt) {}
}
