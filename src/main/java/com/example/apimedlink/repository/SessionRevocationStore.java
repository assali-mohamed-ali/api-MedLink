package com.example.apimedlink.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Review of PR #89: the time before which a user's API sessions are refused, whatever Keycloak says.
 * Each call is its own statement (no transaction around the Keycloak calls between them).
 */
@Repository
public class SessionRevocationStore {

    private final JdbcClient db;

    public SessionRevocationStore(JdbcClient db) {
        this.db = db;
    }

    /**
     * Refuses every session of the user opened until now; returns the mark set. A user without a row in
     * {@code users} has never had an API session: nothing is stored.
     */
    public Instant revoke(UUID userId) {
        // Postgres keeps microseconds: the mark is cut there, so what is read back is what was set
        Instant mark = Instant.now().truncatedTo(ChronoUnit.MICROS);
        db.sql("""
                        INSERT INTO session_revocations (user_id, revoked_at)
                        SELECT id, :mark FROM users WHERE id = :id
                        ON CONFLICT (user_id) DO UPDATE SET revoked_at = EXCLUDED.revoked_at
                        """)
                .param("id", userId)
                .param("mark", OffsetDateTime.ofInstant(mark, ZoneOffset.UTC))
                .update();
        return mark;
    }

    public Optional<Instant> revokedAt(UUID userId) {
        return db.sql("SELECT revoked_at FROM session_revocations WHERE user_id = :id")
                .param("id", userId)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    /**
     * Puts back the mark that was there before {@link #revoke}, when the change it guarded failed. Only
     * while {@code mark} is still the current one: a newer revocation (a staff reset meanwhile) stays.
     */
    public void undo(UUID userId, Instant mark, Optional<Instant> previous) {
        OffsetDateTime ours = OffsetDateTime.ofInstant(mark, ZoneOffset.UTC);
        if (previous.isEmpty()) {
            db.sql("DELETE FROM session_revocations WHERE user_id = :id AND revoked_at = :mark")
                    .param("id", userId)
                    .param("mark", ours)
                    .update();
            return;
        }
        db.sql("UPDATE session_revocations SET revoked_at = :previous WHERE user_id = :id AND revoked_at = :mark")
                .param("id", userId)
                .param("mark", ours)
                .param("previous", OffsetDateTime.ofInstant(previous.get(), ZoneOffset.UTC))
                .update();
    }
}
