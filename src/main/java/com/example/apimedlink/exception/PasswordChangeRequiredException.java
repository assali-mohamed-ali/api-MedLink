package com.example.apimedlink.exception;

/** Keycloak accepted the password but the account has a pending required action (UPDATE_PASSWORD). */
public class PasswordChangeRequiredException extends RuntimeException {

    public PasswordChangeRequiredException() {
        super(null, null, false, false);
    }
}
