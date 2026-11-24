package com.example.apimedlink.model;

import java.time.Instant;
import java.util.UUID;

/**
 * MLink-62/63: a user's staff code, one row of {@code user_mfa}. The secret is sealed by
 * {@code MfaSecretCipher}; it is enabled by the first good code. {@code lastUsedStep} (null before that)
 * makes each code work once; {@code failedAttempts} and {@code lockedUntil} lock the code step after too
 * many wrong codes.
 */
public record UserMfa(
        UUID userId,
        String secretEnc,
        boolean enabled,
        Instant enrolledAt,
        Long lastUsedStep,
        int failedAttempts,
        Instant lockedUntil) {}
