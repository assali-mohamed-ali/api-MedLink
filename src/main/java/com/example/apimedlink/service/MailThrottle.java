package com.example.apimedlink.service;

import com.example.apimedlink.repository.RateLimitStore;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * At most one mail of a kind per address per interval, so nobody can use our forms to flood a
 * stranger's inbox. Counted in Postgres: shared by every API instance, and two requests at once
 * cannot both send (GitHub #51).
 */
@Component
public class MailThrottle {

    private final RateLimitStore store;
    private final Duration interval;

    public MailThrottle(RateLimitStore store, @Value("${app.mail.warning-interval:15m}") Duration interval) {
        this.store = store;
        this.interval = interval;
    }

    /** True, and remembered, when no mail of this kind went to this address within the interval. */
    public boolean allow(String kind, String address) {
        return allow(kind, address, interval);
    }

    /** Same, with this kind's own interval. */
    public boolean allow(String kind, String address, Duration interval) {
        return store.hit("mail:" + kind, address, interval).count() == 1;
    }
}
