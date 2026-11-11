package com.example.apimedlink.security;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A login that Keycloak accepted but that still has UPDATE_PASSWORD pending (MLink-88): the session holds
 * only the user id, for five minutes, until the forced change (endpoint 10). The rules are {@link PendingStep}'s.
 */
@Component
public class PasswordChangePending extends PendingStep<UUID> {

    /** The session attribute, removed when a full session opens ({@link LoginSessions}). */
    public static final String ATTRIBUTE = PasswordChangePending.class.getName();

    public PasswordChangePending(Clock clock) {
        super(clock);
    }
}
