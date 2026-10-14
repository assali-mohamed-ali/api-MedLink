package com.example.apimedlink.exception;

import org.springframework.http.HttpStatus;

/** Keycloak did not answer, or answered something we cannot use. */
public class KeycloakUnavailableException extends ApiException {

    public KeycloakUnavailableException(String reason) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_UNAVAILABLE",
                "Le service d'identité est momentanément indisponible. Réessayez dans un instant.");
        initCause(new IllegalStateException(reason));
    }
}
