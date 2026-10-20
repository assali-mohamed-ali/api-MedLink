package com.example.apimedlink.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

/**
 * Fixed-window counters in Postgres (V8 {@code rate_limits}), shared by every API instance (GitHub #51).
 * One statement per hit, so concurrent hits are counted one after the other by the row lock: no
 * check-then-act race. The database's clock decides the windows, so instances never disagree.
 */
@Repository
public class RateLimitStore {

    private final JdbcClient db;

    public RateLimitStore(JdbcClient db) {
        this.db = db;
    }

    /** The hit's number in the current window, and the seconds until that window ends (at least 1). */
    public record Hit(int count, long secondsLeft) {}

    /**
     * Counts one hit for {@code subject} under {@code kind}; a window of {@code window} starts at the first
     * hit and at the first hit after the previous window ended. Only a hash of the subject is stored.
     */
    public Hit hit(String kind, String subject, Duration window) {
        return db.sql("""
                        INSERT INTO rate_limits (key, hits, expires_at)
                        VALUES (:key, 1, now() + :seconds * INTERVAL '1 second')
                        ON CONFLICT (key) DO UPDATE SET
                            hits = CASE WHEN rate_limits.expires_at <= now() THEN 1 ELSE rate_limits.hits + 1 END,
                            expires_at = CASE WHEN rate_limits.expires_at <= now() THEN EXCLUDED.expires_at
                                              ELSE rate_limits.expires_at END
                        RETURNING hits, GREATEST(1, CEIL(EXTRACT(EPOCH FROM expires_at - now())))::BIGINT AS seconds_left
                        """)
                .param("key", kind + ":" + sha256(subject))
                .param("seconds", window.toSeconds())
                .query((row, n) -> new Hit(row.getInt("hits"), row.getLong("seconds_left")))
                .single();
    }

    /** Ended windows are forgotten; every instance may run it, deleting twice is harmless. */
    @Scheduled(fixedDelayString = "${app.rate-limit.cleanup-interval:10m}")
    public void deleteExpired() {
        db.sql("DELETE FROM rate_limits WHERE expires_at <= now()").update();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JVM", e);
        }
    }
}
