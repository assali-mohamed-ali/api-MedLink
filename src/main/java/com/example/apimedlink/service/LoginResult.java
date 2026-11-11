package com.example.apimedlink.service;

import java.util.UUID;

/**
 * A login either opens a full session, waits for the staff code (practitioners and staff, MLink-62/63), or
 * waits for a forced password change.
 */
public sealed interface LoginResult {

    record Success(Login login) implements LoginResult {}

    /** The password is right; the session opens after a code ({@code setupRequired}: none enrolled yet). */
    record MfaRequired(Login login, boolean setupRequired) implements LoginResult {}

    record PasswordChange(UUID userId) implements LoginResult {}
}
