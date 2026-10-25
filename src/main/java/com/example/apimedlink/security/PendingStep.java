package com.example.apimedlink.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.ClassUtils;

/**
 * A login Keycloak accepted that still has one step before a session opens: a forced password change
 * (MLink-88) or the staff code (MLink-62). The session holds only the step's value, for five minutes, and
 * never an authentication: every other endpoint stays closed. Each kind of step keeps its value under its
 * own class name, so one never reads the other's.
 */
public abstract class PendingStep<T> {

    static final Duration TTL = Duration.ofMinutes(5);

    private final Clock clock;
    private final String attribute;

    protected PendingStep(Clock clock) {
        this.clock = clock;
        // The user class: a Spring proxy subclass keeps the same attribute name
        this.attribute = ClassUtils.getUserClass(getClass()).getName();
    }

    /** Drops any previous session and login (no fixation, no mixing), then holds {@code value}. */
    public void start(T value, HttpServletRequest request) {
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(true);
        session.setAttribute(attribute, new Pending<>(value, clock.instant().plus(TTL)));
        session.setMaxInactiveInterval((int) TTL.toSeconds());
    }

    /** The value while the step is still running; once it has run out, the session is ended. */
    public Optional<T> current(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        Pending<T> pending = (Pending<T>) session.getAttribute(attribute);
        if (pending == null) {
            return Optional.empty();
        }
        if (clock.instant().isAfter(pending.expiresAt())) {
            session.invalidate();
            return Optional.empty();
        }
        return Optional.of(pending.value());
    }

    public void clear(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(attribute);
            session.invalidate();
        }
    }

    record Pending<T>(T value, Instant expiresAt) {}
}
