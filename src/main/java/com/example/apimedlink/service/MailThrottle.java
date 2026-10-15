package com.example.apimedlink.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * At most one mail of a kind per address per interval, so nobody can use our forms to flood a
 * stranger's inbox. In memory: enough for one API instance (plan.md §2, "simple first").
 */
@Component
public class MailThrottle {

    private static final int PRUNE_ABOVE = 10_000;

    private final Duration interval;
    private final Map<String, Instant> lastSent = new ConcurrentHashMap<>();

    public MailThrottle(@Value("${app.mail.warning-interval:15m}") Duration interval) {
        this.interval = interval;
    }

    /** True, and remembered, when no mail of this kind went to this address within the interval. */
    public boolean allow(String kind, String address) {
        Instant now = Instant.now();
        if (lastSent.size() > PRUNE_ABOVE) {
            lastSent.values().removeIf(sent -> sent.plus(interval).isBefore(now));
        }
        boolean[] allowed = {false};
        lastSent.compute(kind + ":" + address, (key, previous) -> {
            if (previous == null || !now.isBefore(previous.plus(interval))) {
                allowed[0] = true;
                return now;
            }
            return previous;
        });
        return allowed[0];
    }
}
