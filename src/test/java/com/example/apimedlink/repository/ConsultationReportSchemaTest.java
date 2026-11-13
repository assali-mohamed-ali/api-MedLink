package com.example.apimedlink.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.model.ConsultationReport;
import java.sql.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MLink-32 (32.1): the consultation reports table, checked in SQL. No plain text column, the author is a
 * practitioner, the content is required, and a report is never changed or deleted (a correction is a new report).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class ConsultationReportSchemaTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ConsultationReportRepository reports;

    @Test
    void theTableHoldsTheContentEncryptedOnlyNeverInPlainText() {
        List<String> columns = jdbc.queryForList("select column_name from information_schema.columns "
                + "where table_name = 'consultation_reports'", String.class);

        assertThat(columns).containsExactlyInAnyOrder(
                "id", "patient_id", "practitioner_id", "appointment_id", "content_encrypted", "created_at");
    }

    @Test
    void aReportIsSavedAndTheListOfAPatientComesNewestFirst() throws Exception {
        UUID practitioner = insertPractitioner();
        UUID patient = insertPatient();
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        reports.saveAndFlush(new ConsultationReport(older, patient, practitioner, "sealed-1"));
        Thread.sleep(5);
        reports.saveAndFlush(new ConsultationReport(newer, patient, practitioner, "sealed-2"));
        reports.saveAndFlush(new ConsultationReport(UUID.randomUUID(), insertPatient(), practitioner, "someone else"));

        List<ConsultationReport> found = reports.findByPatientIdOrderByCreatedAtDesc(patient);

        assertThat(found).extracting(ConsultationReport::getId).containsExactly(newer, older);
        assertThat(found.get(0).getContentEncrypted()).isEqualTo("sealed-2");
        assertThat(found.get(0).getPractitionerId()).isEqualTo(practitioner);
        assertThat(found.get(0).getCreatedAt()).isNotNull();
    }

    /**
     * The time given back when a report is saved is the time stored: PostgreSQL keeps microseconds, and Linux clocks
     * give nanoseconds (the CI's), so the entity keeps microseconds too (the POST's createdAt equals the GET's).
     */
    @Test
    void theCreationTimeKeptByTheEntityIsTheOneTheDatabaseStores() {
        UUID practitioner = insertPractitioner();
        UUID patient = insertPatient();

        ConsultationReport saved = reports.saveAndFlush(new ConsultationReport(UUID.randomUUID(), patient, practitioner, "sealed"));

        assertThat(saved.getCreatedAt().getNano() % 1_000).isZero();
        assertThat(jdbc.queryForObject("select created_at from consultation_reports where id = ?", java.sql.Timestamp.class,
                saved.getId()).toInstant()).isEqualTo(saved.getCreatedAt());
    }

    @Test
    void theAuthorMustBeAPractitioner() {
        UUID patient = insertPatient();
        UUID notAPractitioner = insertPatient();

        assertThatThrownBy(() -> insertReport(patient, notAPractitioner, "sealed"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theContentIsRequired() {
        UUID practitioner = insertPractitioner();
        UUID patient = insertPatient();

        assertThatThrownBy(() -> insertReport(patient, practitioner, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aReportCannotBeChanged() {
        UUID report = insertReport(insertPatient(), insertPractitioner(), "sealed");

        assertThatThrownBy(() -> jdbc.update("update consultation_reports set content_encrypted = 'other' where id = ?", report))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void aReportCannotBeDeleted() {
        UUID report = insertReport(insertPatient(), insertPractitioner(), "sealed");

        assertThatThrownBy(() -> jdbc.update("delete from consultation_reports where id = ?", report))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void thePatientListIsIndexed() {
        List<String> indexes = jdbc.queryForList(
                "select indexdef from pg_indexes where tablename = 'consultation_reports'", String.class);

        assertThat(indexes).anyMatch(index -> index.contains("(patient_id, created_at"));
    }

    private UUID insertReport(UUID patient, UUID practitioner, String content) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into consultation_reports (id, patient_id, practitioner_id, content_encrypted) values (?, ?, ?, ?)",
                id, patient, practitioner, content);
        return id;
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", id, "user-" + id, "user-" + id + "@example.com");
        return id;
    }

    private UUID insertPatient() {
        UUID id = insertUser();
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Test', ?)",
                id, Date.valueOf("1990-04-12"));
        return id;
    }

    private UUID insertPractitioner() {
        UUID id = insertUser();
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Sami', 'Dev', ?)", id, specialty);
        return id;
    }
}
