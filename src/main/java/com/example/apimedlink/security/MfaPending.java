package com.example.apimedlink.security;

import com.example.apimedlink.service.Login;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * A practitioner's or staff member's login whose password Keycloak accepted, waiting for the code (MLink-62/63):
 * the session holds the accepted login, Keycloak tokens included (server side only), for five minutes, until
 * endpoint 9 opens the full session. The rules are {@link PendingStep}'s.
 */
@Component
public class MfaPending extends PendingStep<Login> {

    /** The session attribute, removed when a full session opens ({@link LoginSessions}). */
    public static final String ATTRIBUTE = MfaPending.class.getName();

    public MfaPending(Clock clock) {
        super(clock);
    }
}
