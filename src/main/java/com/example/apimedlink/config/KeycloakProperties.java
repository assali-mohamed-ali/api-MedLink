package com.example.apimedlink.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code keycloak.*} in application.properties. {@code internalUrl} is what the API reaches
 * (docker: http://keycloak:8080); {@code admin} is the service account for admin calls.
 */
@ConfigurationProperties("keycloak")
public record KeycloakProperties(
        String publicUrl, String internalUrl, String realm, String clientId, String clientSecret, Admin admin) {

    public record Admin(String clientId, String clientSecret) {}
}
