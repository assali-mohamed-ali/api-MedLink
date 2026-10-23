package com.example.apimedlink.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.apimedlink.TestcontainersConfiguration;
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
 * The appointment domain tables (MLink-97), checked in SQL: practitioners share their user's id,
 * slots cannot overlap for one practitioner or end before they start, appointments belong to a patient,
 * and a slot holds one active appointment at most (review on PR #87).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class AppointmentSchemaTest {

    private static final Instant NINE_AM = Instant.parse("2030-01-07T08:00:00Z");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theSixTablesOfTheDomainExist() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);

        assertThat(tables).contains(
                "clinics", "specialties", "practitioners", "practitioner_clinics", "slots", "appointments");
    }

    @Test
    void aPractitionerIsAUserWithTheSameId() {
        UUID id = insertPractitioner();

        assertThat(jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from practitioners where id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void aPractitionerWithoutAUserRowIsRefused() {
        UUID specialty = insertSpecialty();

        assertThatThrownBy(() -> jdbc.update(
                "insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'No', 'User', ?)",
                UUID.randomUUID(), specialty))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onePractitionerCannotHaveTwoSlotsStartingAtTheSameTime() {
        UUID practitioner = insertPractitioner();
        UUID clinic = insertClinic();
        insertSlot(practitioner, clinic, NINE_AM, NINE_AM.plus(30, ChronoUnit.MINUTES), "FREE");

        assertThatThrownBy(() ->
                insertSlot(practitioner, clinic, NINE_AM, NINE_AM.plus(30, ChronoUnit.MINUTES), "FREE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSlotCannotEndBeforeItStarts() {
        UUID practitioner = insertPractitioner();
        UUID clinic = insertClinic();

        assertThatThrownBy(() -> insertSlot(practitioner, clinic, NINE_AM, NINE_AM.minusSeconds(60), "FREE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSlotStatusIsFreeOrBooked() {
        UUID practitioner = insertPractitioner();
        UUID clinic = insertClinic();

        assertThatThrownBy(() ->
                insertSlot(practitioner, clinic, NINE_AM, NINE_AM.plus(30, ChronoUnit.MINUTES), "HELD"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anAppointmentIsHeldByAPatientAndRemembersWhoBookedIt() {
        UUID slot = insertSlot(insertPractitioner(), insertClinic(),
                NINE_AM, NINE_AM.plus(30, ChronoUnit.MINUTES), "BOOKED");
        UUID patient = insertPatient();

        jdbc.update("insert into appointments (id, slot_id, patient_id, booked_by, status) values (?, ?, ?, ?, 'BOOKED')",
                UUID.randomUUID(), slot, patient, patient);

        assertThat(jdbc.queryForObject("select count(*) from appointments where patient_id = ?", Integer.class,
                patient)).isEqualTo(1);
    }

    @Test
    void anAppointmentCannotBeHeldByAUserWhoIsNotAPatient() {
        UUID practitioner = insertPractitioner();
        UUID slot = insertSlot(practitioner, insertClinic(), NINE_AM, NINE_AM.plus(30, ChronoUnit.MINUTES), "BOOKED");

        assertThatThrownBy(() -> jdbc.update(
                "insert into appointments (id, slot_id, patient_id, booked_by, status) values (?, ?, ?, ?, 'BOOKED')",
                UUID.randomUUID(), slot, practitioner, practitioner))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSlotHoldsOneActiveAppointmentOnly() {
        UUID slot = insertSlot(insertPractitioner(), insertClinic(),
                NINE_AM, NINE_AM.plus(30, ChronoUnit.MINUTES), "BOOKED");
        insertAppointment(slot, insertPatient(), "BOOKED");
        UUID secondPatient = insertPatient();

        assertThatThrownBy(() -> insertAppointment(slot, secondPatient, "BOOKED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aCancelledAppointmentDoesNotBlockANewBookingOfItsSlot() {
        UUID slot = insertSlot(insertPractitioner(), insertClinic(),
                NINE_AM, NINE_AM.plus(30, ChronoUnit.MINUTES), "BOOKED");
        insertAppointment(slot, insertPatient(), "CANCELLED");

        insertAppointment(slot, insertPatient(), "BOOKED");

        assertThat(jdbc.queryForObject("select count(*) from appointments where slot_id = ?", Integer.class, slot))
                .isEqualTo(2);
    }

    @Test
    void outsideTheDevProfileThereIsNoSeedData() {
        assertThat(jdbc.queryForObject("select count(*) from clinics", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from practitioners", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from slots", Integer.class)).isZero();
    }

    private void insertAppointment(UUID slot, UUID patient, String status) {
        jdbc.update("insert into appointments (id, slot_id, patient_id, booked_by, status) values (?, ?, ?, ?, ?)",
                UUID.randomUUID(), slot, patient, patient, status);
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)",
                id, "user-" + id, "user-" + id + "@example.com");
        return id;
    }

    private UUID insertPatient() {
        UUID id = insertUser();
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Ben Salah', ?)",
                id, Date.valueOf("1990-04-12"));
        return id;
    }

    private UUID insertSpecialty() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", id, "Specialty " + id);
        return id;
    }

    private UUID insertPractitioner() {
        UUID id = insertUser();
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Sami', 'Dev', ?)",
                id, insertSpecialty());
        return id;
    }

    private UUID insertClinic() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, 'Clinique test', 'Sousse')", id);
        return id;
    }

    private UUID insertSlot(UUID practitioner, UUID clinic, Instant start, Instant end, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, ?)",
                id, practitioner, clinic, Timestamp.from(start), Timestamp.from(end), status);
        return id;
    }
}
