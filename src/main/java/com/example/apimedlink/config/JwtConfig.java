package com.example.apimedlink.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtConfig {

    /** Base64 or plain secret — surcharge via env JWT_SECRET. */
    private String secret = "change-me-please-use-env-JWT_SECRET-32-chars-min";

    /** Durée de validité en ms (défaut 1h). */
    private long expirationMs = 3600000L;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    public void setExpirationMs(long expirationMs) {
        this.expirationMs = expirationMs;
    }
}
