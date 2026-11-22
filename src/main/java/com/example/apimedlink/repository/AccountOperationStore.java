package com.example.apimedlink.repository;

import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * MLink-72 (review of #122): the durable record of the account operations that reach Keycloak, and the audit
 * entries that carry their operation id. Each call is its own statement; a caller that needs several of them to be
 * kept or lost together runs them inside one transaction. No transaction is held while Keycloak answers: an
 * operation is claimed with a short lease (locked_until) and a fresh token (lease_token), carried out, then closed:
 * closing it, or putting it back to wait, only works for the holder of the current token, so a worker whose lease
 * ended and whose operation was taken over by another worker cannot change it any more.
 */
@Repository
public class AccountOperationStore {

    private static final String COLUMNS =
            "id, action, user_id, target_role, specialty_id, clinic_ids, enabled, requested_by, ip_address, attempts, "
                    + "lease_token";

    private final JdbcClient db;

    public AccountOperationStore(JdbcClient db) {
        this.db = db;
    }

    /** Registers a PENDING operation, due now. The unique index refuses a second pending one for the same account. */
    public void insert(AccountOperation op, Instant now) {
        db.sql("""
                        INSERT INTO account_operations (id, action, user_id, target_role, specialty_id, clinic_ids,
                                                        enabled, requested_by, ip_address, status, attempts,
                                                        next_attempt_at, created_at)
                        VALUES (:id, :action, :target, :role, :specialty, :clinics, :enabled, :by, :ip,
                                'PENDING', 0, :now, :now)
                        """)
                .param("id", op.id(), Types.OTHER)
                .param("action", op.action().name(), Types.VARCHAR)
                .param("target", op.userId(), Types.OTHER)
                .param("role", op.targetRole(), Types.VARCHAR)
                .param("specialty", op.specialtyId(), Types.OTHER)
                .param("clinics", clinicsText(op.clinicIds()), Types.VARCHAR)
                .param("enabled", op.enabled(), Types.BOOLEAN)
                .param("by", op.requestedBy(), Types.OTHER)
                .param("ip", op.ipAddress(), Types.VARCHAR)
                .param("now", utc(now))
                .update();
    }

    public Optional<AccountOperation> findPendingFor(UUID userId) {
        return db.sql("SELECT " + COLUMNS + " FROM account_operations WHERE user_id = :id AND status = 'PENDING'")
                .param("id", userId, Types.OTHER)
                .query((rs, row) -> map(rs))
                .optional();
    }

    /**
     * Takes this operation for {@code leaseSeconds}, with a new token, and counts the try. Empty when it is no longer
     * pending or when another run holds it (its lease has not ended).
     */
    public Optional<AccountOperation> lease(UUID id, Instant now, long leaseSeconds) {
        return db.sql("""
                        UPDATE account_operations SET locked_until = :until, attempts = attempts + 1,
                                                      lease_token = :token
                        WHERE id = :id AND status = 'PENDING' AND (locked_until IS NULL OR locked_until < :now)
                        RETURNING\s""" + COLUMNS)
                .param("id", id, Types.OTHER)
                .param("token", UUID.randomUUID(), Types.OTHER)
                .param("until", utc(now.plusSeconds(leaseSeconds)))
                .param("now", utc(now))
                .query((rs, row) -> map(rs))
                .optional();
    }

    /**
     * Takes the oldest operation that is due and not held, with a lease and a new token. Called just before each
     * operation runs, never for a whole batch at once: the lease of an operation that is still waiting its turn
     * would otherwise run out while the ones before it are carried out. Several workers never take the same one.
     */
    public Optional<AccountOperation> claimNext(Instant now, long leaseSeconds) {
        return db.sql("""
                        UPDATE account_operations SET locked_until = :until, attempts = attempts + 1,
                                                      lease_token = :token
                        WHERE id = (SELECT id FROM account_operations
                                    WHERE status = 'PENDING' AND next_attempt_at <= :now
                                      AND (locked_until IS NULL OR locked_until < :now)
                                    ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED)
                        RETURNING\s""" + COLUMNS)
                .param("token", UUID.randomUUID(), Types.OTHER)
                .param("until", utc(now.plusSeconds(leaseSeconds)))
                .param("now", utc(now))
                .query((rs, row) -> map(rs))
                .optional();
    }

    /**
     * Closes the operation (DONE or FAILED) for the holder of {@code leaseToken}; false when it was already closed
     * or taken over by another claim, so nothing is written twice and a stale worker changes nothing.
     */
    public boolean close(UUID id, UUID leaseToken, String status, Instant now, String lastError) {
        return db.sql("""
                        UPDATE account_operations SET status = :status, closed_at = :now, locked_until = NULL,
                                                      last_error = :error
                        WHERE id = :id AND status = 'PENDING' AND lease_token = :token
                        """)
                .param("id", id, Types.OTHER)
                .param("token", leaseToken, Types.OTHER)
                .param("status", status, Types.VARCHAR)
                .param("now", utc(now))
                .param("error", lastError, Types.VARCHAR)
                .update() == 1;
    }

    /**
     * Puts the operation back to wait, for the holder of {@code leaseToken}: it will be tried again at
     * {@code next}, and nothing holds it any more. False (nothing changed) when another claim holds it now.
     */
    public boolean reschedule(UUID id, UUID leaseToken, Instant next, String lastError) {
        return db.sql("""
                        UPDATE account_operations SET next_attempt_at = :next, locked_until = NULL, last_error = :error
                        WHERE id = :id AND status = 'PENDING' AND lease_token = :token
                        """)
                .param("id", id, Types.OTHER)
                .param("token", leaseToken, Types.OTHER)
                .param("next", utc(next))
                .param("error", lastError, Types.VARCHAR)
                .update() == 1;
    }

    /**
     * One audit entry of an operation, with its operation id. Written by this statement, not by AuditService,
     * because the closing entry is written by a worker that has no request: the caller and the IP are those of the
     * request that started the operation, kept in the operation. Only ids, never a name, an e-mail or a token.
     */
    public void appendAudit(UUID operationId, AuditAction action, UUID userId, UUID actor, String ip,
                            AuditOutcome outcome) {
        db.sql("""
                        INSERT INTO audit_entries (id, timestamp, user_id, action, resource_type, resource_id,
                                                   ip_address, outcome, operation_id)
                        VALUES (:id, :ts, :actor, :action, 'USER', :resource, :ip, :outcome, :operation)
                        """)
                .param("id", UUID.randomUUID(), Types.OTHER)
                .param("ts", utc(Instant.now()))
                .param("actor", actor, Types.OTHER)
                .param("action", action.name(), Types.VARCHAR)
                .param("resource", userId.toString(), Types.VARCHAR)
                .param("ip", ip, Types.VARCHAR)
                .param("outcome", outcome.name(), Types.VARCHAR)
                .param("operation", operationId, Types.OTHER)
                .update();
    }

    private static AccountOperation map(ResultSet rs) throws SQLException {
        String clinics = rs.getString("clinic_ids");
        Set<UUID> clinicIds = clinics == null || clinics.isBlank()
                ? Set.of()
                : Arrays.stream(clinics.split(",")).map(UUID::fromString).collect(Collectors.toSet());
        return new AccountOperation(
                rs.getObject("id", UUID.class),
                AuditAction.valueOf(rs.getString("action")),
                rs.getObject("user_id", UUID.class),
                rs.getString("target_role"),
                rs.getObject("specialty_id", UUID.class),
                clinicIds,
                rs.getObject("enabled", Boolean.class),
                rs.getObject("requested_by", UUID.class),
                rs.getString("ip_address"),
                rs.getInt("attempts"),
                rs.getObject("lease_token", UUID.class));
    }

    private static String clinicsText(Set<UUID> clinicIds) {
        return clinicIds.isEmpty()
                ? null
                : clinicIds.stream().map(UUID::toString).sorted().collect(Collectors.joining(","));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
