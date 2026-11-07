package com.example.apimedlink.repository;

import com.example.apimedlink.model.UserMfa;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * MLink-62/63: the staff code row ({@code user_mfa}). Each rule is one statement, so it holds under
 * concurrent requests without a transaction: Postgres locks the row and re-checks the condition. The limits
 * and times are the caller's ({@code MfaService}), so this class only stores.
 */
@Repository
public class UserMfaStore {

    /** Wrong codes counted so far, and the end of the lock once they reached the limit (else null). */
    public record Failures(int count, Instant lockedUntil) {}

    private final JdbcClient db;

    public UserMfaStore(JdbcClient db) {
        this.db = db;
    }

    public Optional<UserMfa> find(UUID userId) {
        return db.sql("""
                        SELECT user_id, secret_enc, enabled, enrolled_at, last_used_step, failed_attempts, locked_until
                        FROM user_mfa WHERE user_id = :id
                        """)
                .param("id", userId)
                .query(UserMfaStore::row)
                .optional();
    }

    public boolean isEnabled(UUID userId) {
        return db.sql("SELECT enabled FROM user_mfa WHERE user_id = :id")
                .param("id", userId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    /**
     * Stores a new secret, not yet enabled, replacing one that was never used. Returns false, and changes
     * nothing, when the user already has an enabled code. The wrong-code count is kept: a new setup does not
     * lift a lock.
     */
    public boolean saveSecret(UUID userId, String secretEnc) {
        return db.sql("""
                        INSERT INTO user_mfa (user_id, secret_enc) VALUES (:id, :secret)
                        ON CONFLICT (user_id) DO UPDATE SET secret_enc = EXCLUDED.secret_enc
                        WHERE user_mfa.enabled = FALSE
                        """)
                .param("id", userId)
                .param("secret", secretEnc)
                .update() == 1;
    }

    /**
     * Accepts a good code: enables the secret (first time), records the step and clears the wrong-code
     * count. Returns false, and changes nothing, when that step or a later one was already used, or while
     * the code step is locked at {@code at}.
     */
    public boolean markUsed(UUID userId, long step, Instant at) {
        return db.sql("""
                        UPDATE user_mfa
                        SET enabled = TRUE, enrolled_at = COALESCE(enrolled_at, :at), last_used_step = :step,
                            failed_attempts = 0, locked_until = NULL
                        WHERE user_id = :id
                          AND (last_used_step IS NULL OR last_used_step < :step)
                          AND (locked_until IS NULL OR locked_until <= :at)
                        """)
                .param("id", userId)
                .param("step", step)
                .param("at", utc(at))
                .update() == 1;
    }

    /**
     * Counts a wrong code at {@code now}. The {@code max}-th sets the lock until {@code lockUntil}; while a
     * lock runs nothing more is counted; once it has ended a new count starts. Empty when the user has no
     * secret (nothing to guess).
     */
    public Optional<Failures> registerFailure(UUID userId, int max, Instant now, Instant lockUntil) {
        // In SET, columns are the values before this update; CASE on a NULL lock falls to ELSE
        return db.sql("""
                        UPDATE user_mfa
                        SET failed_attempts = CASE
                                WHEN locked_until > :now THEN failed_attempts
                                WHEN locked_until <= :now THEN 1
                                ELSE failed_attempts + 1 END,
                            locked_until = CASE
                                WHEN locked_until > :now THEN locked_until
                                WHEN (CASE WHEN locked_until <= :now THEN 1 ELSE failed_attempts + 1 END) >= :max
                                    THEN :lockUntil
                                ELSE NULL END
                        WHERE user_id = :id
                        RETURNING failed_attempts, locked_until
                        """)
                .param("id", userId)
                .param("max", max)
                .param("now", utc(now))
                .param("lockUntil", utc(lockUntil))
                .query((rs, n) -> new Failures(rs.getInt("failed_attempts"), instant(rs, "locked_until")))
                .optional();
    }

    private static UserMfa row(ResultSet rs, int n) throws SQLException {
        return new UserMfa(
                rs.getObject("user_id", UUID.class),
                rs.getString("secret_enc"),
                rs.getBoolean("enabled"),
                instant(rs, "enrolled_at"),
                rs.getObject("last_used_step", Long.class),
                rs.getInt("failed_attempts"),
                instant(rs, "locked_until"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
