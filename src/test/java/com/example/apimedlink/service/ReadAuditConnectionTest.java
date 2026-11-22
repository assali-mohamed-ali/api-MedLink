package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditEntry;
import com.example.apimedlink.repository.AuditEntryRepository;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Review on #123 and on #90: a traced read (the reports, MLink-32, the record, MLink-31, and the users, MLink-114)
 * must not keep its read transaction, and so its database connection, open while the audit row is written in its
 * own transaction. Proven with a pool of ONE connection: a read that still held its connection while asking for the
 * audit's would wait for itself until the pool's timeout and fail. The row is read back afterwards through the same
 * pool, so it is committed (durable).
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.connection-timeout=2000",
        "app.rate-limit.cleanup-interval=1h"})
@Import(TestcontainersConfiguration.class)
class ReadAuditConnectionTest {

    @Autowired
    ConsultationReportService reports;

    @Autowired
    PatientRecordService records;

    @Autowired
    UserService userService;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    AuditEntryRepository entries;

    @Test
    void readingTheReportsNeedsOneConnectionAtATimeAndItsTraceIsCommitted() {
        UUID pro = practitioner();
        UUID patient = patientAttachedTo(pro);
        reports.create(pro, patient, "Compte-rendu lu avec une seule connexion.");

        assertThat(reports.list(pro, patient)).hasSize(1);

        assertThat(viewed("REPORT_VIEWED", patient)).isEqualTo(1);
    }

    @Test
    void readingTheRecordNeedsOneConnectionAtATimeAndItsTraceIsCommitted() {
        UUID pro = practitioner();
        UUID patient = patientAttachedTo(pro);

        assertThat(records.read(pro, patient)).isNotNull();

        assertThat(viewed("RECORD_VIEWED", patient)).isEqualTo(1);
    }

    /** Review on #90: the list of users is read and traced with one connection at a time. */
    @Test
    void readingAllTheUsersNeedsOneConnectionAtATimeAndItsTraceIsCommitted() {
        UUID pro = practitioner();
        int before = traced("DATA_READ", "ALL");

        assertThat(userService.findAll()).extracting(user -> user.getId()).contains(pro);

        assertThat(traced("DATA_READ", "ALL")).isEqualTo(before + 1);
    }

    /** Review on #90: one user is read and traced with one connection at a time. */
    @Test
    void readingOneUserNeedsOneConnectionAtATimeAndItsTraceIsCommitted() {
        UUID pro = practitioner();

        assertThat(userService.findById(pro).getId()).isEqualTo(pro);

        assertThat(traced("DATA_READ", pro.toString())).isEqualTo(1);
    }

    /** Still the rule of 32.4: a read whose trace cannot be written is not answered. */
    @Test
    void aReadWhoseTraceCannotBeWrittenIsNotAnswered() {
        UUID pro = practitioner();
        UUID patient = patientAttachedTo(pro);
        doThrow(new DataAccessResourceFailureException("audit store unavailable"))
                .when(entries).save(argThat((AuditEntry entry) -> entry != null
                        && (entry.getAction() == AuditAction.REPORT_VIEWED || entry.getAction() == AuditAction.RECORD_VIEWED)));

        assertThatThrownBy(() -> reports.list(pro, patient)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThatThrownBy(() -> records.read(pro, patient)).isInstanceOf(DataAccessResourceFailureException.class);
    }

    /** The same rule for the users (review on #90): no trace, no answer. */
    @Test
    void aUserReadWhoseTraceCannotBeWrittenIsNotAnswered() {
        UUID pro = practitioner();
        doThrow(new DataAccessResourceFailureException("audit store unavailable"))
                .when(entries).save(argThat((AuditEntry entry) -> entry != null
                        && entry.getAction() == AuditAction.DATA_READ));

        assertThatThrownBy(() -> userService.findAll()).isInstanceOf(DataAccessResourceFailureException.class);
        assertThatThrownBy(() -> userService.findById(pro)).isInstanceOf(DataAccessResourceFailureException.class);
    }

    private Integer viewed(String action, UUID patient) {
        return jdbc.queryForObject("select count(*) from audit_entries where action = ? and resource_id = ?",
                Integer.class, action, patient.toString());
    }

    private int traced(String action, String resourceId) {
        Integer count = jdbc.queryForObject("select count(*) from audit_entries where action = ? and resource_id = ?",
                Integer.class, action, resourceId);
        return count == null ? 0 : count;
    }

    private UUID practitioner() {
        UUID id = UUID.randomUUID();
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", id, "pro-" + id, "pro-" + id + "@example.com");
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Sami', 'Dev', ?)", id, specialty);
        return id;
    }

    private UUID patientAttachedTo(UUID practitioner) {
        UUID patient = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", patient, "patient-" + patient, "patient-" + patient + "@example.com");
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Ben Salah', ?)",
                patient, Date.valueOf("1990-04-12"));
        UUID clinic = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, ?, 'Sousse')", clinic, "Clinique " + clinic);
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", practitioner, clinic);
        UUID slot = UUID.randomUUID();
        Instant start = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(6, ChronoUnit.DAYS).plus(9, ChronoUnit.HOURS);
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, 'BOOKED')",
                slot, practitioner, clinic, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)));
        jdbc.update("insert into appointments (id, slot_id, patient_id, booked_by, status) values (?, ?, ?, ?, 'BOOKED')",
                UUID.randomUUID(), slot, patient, patient);
        return patient;
    }
}
