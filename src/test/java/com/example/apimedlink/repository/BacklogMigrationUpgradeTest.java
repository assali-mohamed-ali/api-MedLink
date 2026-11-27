package com.example.apimedlink.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Upgrade a populated pre-backlog schema using real PostgreSQL; no production data. */
class BacklogMigrationUpgradeTest {

    @Test
    void upgradesVersion23AndPreservesExistingPatient() throws Exception {
        try (var postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16"))) {
            postgres.start();
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").target(MigrationVersion.fromVersion("23")).load().migrate();
            UUID patient = UUID.randomUUID();
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword())) {
                try (var statement = connection.prepareStatement(
                        "INSERT INTO users(id, username, email) VALUES (?, ?, ?)")) {
                    statement.setObject(1, patient);
                    statement.setString(2, "migration@example.invalid");
                    statement.setString(3, "migration@example.invalid");
                    statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement(
                        "INSERT INTO patients(id, first_name, last_name, birth_date) VALUES (?, 'Test', 'Migration', DATE '1990-01-01')")) {
                    statement.setObject(1, patient);
                    statement.executeUpdate();
                }
            }
            Flyway upgraded = Flyway.configure().dataSource(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword()).locations("classpath:db/migration").load();
            upgraded.migrate();
            upgraded.validate();
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword())) {
                try (var statement = connection.prepareStatement(
                        "SELECT first_name, record_number, notify_exam_results, notify_appointment_reminders FROM patients WHERE id = ?")) {
                    statement.setObject(1, patient);
                    try (var rows = statement.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString("first_name")).isEqualTo("Test");
                        assertThat(rows.getLong("record_number")).isPositive();
                        assertThat(rows.getBoolean("notify_exam_results")).isTrue();
                        assertThat(rows.getBoolean("notify_appointment_reminders")).isTrue();
                    }
                }
                try (var statement = connection.createStatement();
                        var rows = statement.executeQuery("SELECT to_regclass('patient_email_changes'), to_regclass('monthly_stats_releases')")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("patient_email_changes");
                    assertThat(rows.getString(2)).isEqualTo("monthly_stats_releases");
                }
            }
        }
    }
}
