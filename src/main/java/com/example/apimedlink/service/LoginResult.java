package com.example.apimedlink.service;

import java.util.UUID;

/** A login either opens a full session or a short pending password change. */
public sealed interface LoginResult {

    record Success(Login login) implements LoginResult {}

    record PasswordChange(UUID userId) implements LoginResult {}
}
