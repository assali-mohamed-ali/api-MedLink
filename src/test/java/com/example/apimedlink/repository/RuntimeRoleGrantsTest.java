package com.example.apimedlink.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.TestcontainersConfiguration;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GitHub #57 (MLink-165): the application connects as a runtime role that can use the schema but not change it.
 * Flyway owns the tables; the app's role only inserts and reads the audit trail, so even a compromised application
 * cannot rewrite it (the V7 triggers alone do not stop a table owner, who can drop or disable them).
 *
 * <p>Every statement goes through the application's own datasource. A refusal must be a missing privilege
 * (SQLState 42501), not the V7 trigger's exception (P0001): the trigger is the second wall, not this one. Each
 * statement runs in a transaction that is always rolled back, so a statement that is wrongly accepted leaves the
 * shared test database as it was.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RuntimeRoleGrantsTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void theApplicationCanAppendToTheAuditTrailAndReadIt() {
        UUID id = UUID.randomUUID();

        Integer found = rolledBack(() -> {
            jdbc.update("INSERT INTO audit_entries (id, timestamp, action, resource_type, resource_id, outcome)"
                    + " VALUES (?, ?, 'B1_PROBE', 'TEST', 'b1', 'SUCCESS')", id, Timestamp.from(Instant.now()));
            return jdbc.queryForObject("SELECT count(*) FROM audit_entries WHERE id = ?", Integer.class, id);
        });

        assertThat(found).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE monthly_stats_releases SET csv = NULL",
            "DELETE FROM monthly_stats_releases",
            "TRUNCATE monthly_stats_releases",
            "UPDATE audit_entries SET outcome = 'REWRITTEN'",
            "DELETE FROM audit_entries",
            "TRUNCATE audit_entries",
            "DROP TRIGGER audit_entries_no_update_or_delete ON audit_entries",
            "ALTER TABLE audit_entries DISABLE TRIGGER audit_entries_no_truncate",
            "DROP TABLE audit_entries",
            "CREATE TABLE b1_probe (id INT)"})
    void theApplicationCannotRewriteTheAuditTrailNorChangeTheSchema(String statement) {
        assertThat(sqlStateOf(statement)).as(statement).isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    /** The SQLState the database answered, or "accepted" when the statement went through (then rolled back). */
    private String sqlStateOf(String statement) {
        try {
            rolledBack(() -> {
                jdbc.execute(statement);
                return null;
            });
            return "accepted";
        } catch (DataAccessException refused) {
            return refused.getMostSpecificCause() instanceof SQLException sql ? sql.getSQLState() : refused.toString();
        }
    }

    private <T> T rolledBack(Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(status -> {
            status.setRollbackOnly();
            return work.get();
        });
    }
}
