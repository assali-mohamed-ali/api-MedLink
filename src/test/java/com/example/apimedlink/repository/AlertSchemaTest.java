package com.example.apimedlink.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.model.Alert;
import com.example.apimedlink.model.AlertType;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MLink-37 (37.1): the alerts table, checked in SQL. An alert is kept until it is read, so a practitioner who was
 * offline still finds it (H-06); it belongs to a practitioner, points to its appointment, and a cancellation gives one
 * alert only, whatever happens twice.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class AlertSchemaTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AlertRepository alerts;

    @Test
    void theTableHasTheColumnsOfTheTicket() {
        List<String> columns = jdbc.queryForList("select column_name from information_schema.columns "
                + "where table_name = 'alerts'", String.class);

        assertThat(columns).containsExactlyInAnyOrder(
                "id", "practitioner_id", "type", "appointment_id", "message", "created_at", "read_at");
    }

    @Test
    void theUnreadAlertsOfAPractitionerComeNewestFirstWithoutTheReadOnesOrAnyoneElses() throws Exception {
        UUID pro = insertPractitioner();
        UUID other = insertPractitioner();
        Alert older = alerts.saveAndFlush(new Alert(pro, AlertType.APPOINTMENT_CANCELLED, insertAppointment(pro), "Annulé 1"));
        Thread.sleep(5);
        Alert newer = alerts.saveAndFlush(new Alert(pro, AlertType.APPOINTMENT_CANCELLED, insertAppointment(pro), "Annulé 2"));
        Alert read = alerts.saveAndFlush(new Alert(pro, AlertType.APPOINTMENT_CANCELLED, insertAppointment(pro), "Lu"));
        read.markRead(Instant.now());
        alerts.saveAndFlush(read);
        alerts.saveAndFlush(new Alert(other, AlertType.APPOINTMENT_CANCELLED, insertAppointment(other), "Pas à lui"));

        List<Alert> unread = alerts.findByPractitionerIdAndReadAtIsNullOrderByCreatedAtDesc(pro);

        assertThat(unread).extracting(Alert::getId).containsExactly(newer.getId(), older.getId());
        assertThat(unread.get(0).getMessage()).isEqualTo("Annulé 2");
        assertThat(unread.get(0).getType()).isEqualTo(AlertType.APPOINTMENT_CANCELLED);
        assertThat(unread.get(0).getCreatedAt()).isNotNull();
        assertThat(unread.get(0).getReadAt()).isNull();
    }

    @Test
    void anAlertReadOnceKeepsItsFirstReadingTime() {
        UUID pro = insertPractitioner();
        Alert alert = alerts.saveAndFlush(new Alert(pro, AlertType.APPOINTMENT_CANCELLED, insertAppointment(pro), "Annulé"));
        Instant first = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

        alert.markRead(first);
        alert.markRead(Instant.now());
        alerts.saveAndFlush(alert);

        assertThat(jdbc.queryForObject("select read_at from alerts where id = ?", Timestamp.class, alert.getId()).toInstant())
                .isEqualTo(first);
    }

    /** The creation time kept by the entity is the one stored: microseconds, as PostgreSQL (Linux clocks give nanoseconds). */
    @Test
    void theCreationTimeKeptByTheEntityIsTheOneTheDatabaseStores() {
        UUID pro = insertPractitioner();

        Alert saved = alerts.saveAndFlush(new Alert(pro, AlertType.APPOINTMENT_CANCELLED, insertAppointment(pro), "Annulé"));

        assertThat(saved.getCreatedAt().getNano() % 1_000).isZero();
        assertThat(jdbc.queryForObject("select created_at from alerts where id = ?", Timestamp.class, saved.getId()).toInstant())
                .isEqualTo(saved.getCreatedAt());
    }

    @Test
    void oneAppointmentGivesOneCancellationAlertOnly() {
        UUID pro = insertPractitioner();
        UUID appointment = insertAppointment(pro);
        alerts.saveAndFlush(new Alert(pro, AlertType.APPOINTMENT_CANCELLED, appointment, "Annulé"));
        // Read before the refused insert: in PostgreSQL a refused statement ends the test's transaction
        assertThat(alerts.existsByTypeAndAppointmentId(AlertType.APPOINTMENT_CANCELLED, appointment)).isTrue();

        assertThatThrownBy(() -> alerts.saveAndFlush(new Alert(pro, AlertType.APPOINTMENT_CANCELLED, appointment, "Encore")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** One refused statement per test: in PostgreSQL it ends the test's transaction (@DataJpaTest runs each test in one). */
    @Test
    void theOwnerMustBeAPractitioner() {
        UUID pro = insertPractitioner();
        UUID appointment = insertAppointment(pro);
        UUID notAPractitioner = insertUser();

        assertThatThrownBy(() -> jdbc.update("insert into alerts (id, practitioner_id, type, appointment_id, message) "
                + "values (?, ?, 'APPOINTMENT_CANCELLED', ?, 'Annulé')", UUID.randomUUID(), notAPractitioner, appointment))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theMessageIsRequired() {
        UUID pro = insertPractitioner();
        UUID appointment = insertAppointment(pro);

        assertThatThrownBy(() -> jdbc.update("insert into alerts (id, practitioner_id, type, appointment_id, message) "
                + "values (?, ?, 'APPOINTMENT_CANCELLED', ?, null)", UUID.randomUUID(), pro, appointment))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anUnknownTypeIsRefused() {
        UUID pro = insertPractitioner();

        assertThatThrownBy(() -> jdbc.update("insert into alerts (id, practitioner_id, type, appointment_id, message) "
                + "values (?, ?, 'SOMETHING_ELSE', ?, 'Annulé')", UUID.randomUUID(), pro, insertAppointment(pro)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", id, "user-" + id, "user-" + id + "@example.com");
        return id;
    }

    private UUID insertPractitioner() {
        UUID id = insertUser();
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Sami', 'Dev', ?)", id, specialty);
        return id;
    }

    /** One of the practitioner's slots, a patient's appointment on it. */
    private UUID insertAppointment(UUID practitioner) {
        UUID patient = insertUser();
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Ben Salah', ?)",
                patient, Date.valueOf("1990-04-12"));
        UUID clinic = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, ?, 'Sousse')", clinic, "Clinique " + clinic);
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", practitioner, clinic);
        UUID slot = UUID.randomUUID();
        Instant start = Instant.now().plus(3, ChronoUnit.DAYS).plus((long) (Math.random() * 10_000), ChronoUnit.MINUTES);
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, 'FREE')",
                slot, practitioner, clinic, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)));
        UUID appointment = UUID.randomUUID();
        jdbc.update("insert into appointments (id, slot_id, patient_id, booked_by, status) values (?, ?, ?, ?, 'CANCELLED')",
                appointment, slot, patient, patient);
        return appointment;
    }
}
