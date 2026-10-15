package com.example.apimedlink.service;

import java.time.Instant;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * What Keycloak gives at login: the access and ID tokens (signature already checked) and the
 * refresh token. They stay in the server session; never print them.
 */
public record KeycloakTokens(Jwt accessToken, Jwt idToken, String refreshToken, Instant refreshExpiresAt) {

    @Override
    public String toString() {
        return "KeycloakTokens[subject=" + accessToken.getSubject() + ", tokens=***]";
    }
}
