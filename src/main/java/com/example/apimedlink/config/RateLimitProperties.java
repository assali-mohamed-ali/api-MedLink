package com.example.apimedlink.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-IP request limits on the public auth endpoints ({@code app.rate-limit.*}, GitHub #51): at most
 * {@code max} requests from one IP in a {@code window}. Values and reasons: plan.md §4.
 */
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(Rule login, Rule register, Rule resend) {

    public record Rule(int max, Duration window) {}
}
