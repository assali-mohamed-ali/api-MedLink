package com.example.apimedlink.exception;

/**
 * Keycloak already has an account with this email. An internal signal, never an answer: the
 * sign-up answers the same as for a new account, so nobody can learn which emails are
 * registered (code owner's review on #32). Deliberately not an {@link ApiException}: if it ever
 * escaped, the client would get a generic 500, not "this email exists".
 */
public class EmailTakenException extends RuntimeException {

    public EmailTakenException() {
        super("Keycloak already has an account with this email", null, false, false);
    }
}
