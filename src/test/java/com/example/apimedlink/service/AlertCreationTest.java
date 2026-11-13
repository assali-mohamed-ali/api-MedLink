package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.model.Appointment;
import com.example.apimedlink.repository.AppointmentRepository;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * MLink-37 (37.2): a cancelled appointment gives one alert to the practitioner of its slot, written in French with
 * what they need to know (who, when in Tunis time, where). The cancel route belongs to US-15 (epic MLink-8), not merged
 * yet: until then, the method its cancel code will call is tested directly, as the ticket says.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AlertCreationTest {

    /** Thursday 7 January 2027, 08:00 UTC = 09:00 in Tunis; one hour more for each test slot (never two at once). */
    static final Instant FIRST_SLOT = Instant.parse("2027-01-07T08:00:00Z");
    static final AtomicInteger NEXT_SLOT = new AtomicInteger();

    @Autowired
    AlertService alerts;

    @Autowired
    AppointmentRepository appointments;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    MailService mails;

    @Test
    void aCancelledAppointmentGivesOneAlertToThePractitionerOfItsSlotInFrench() {
        UUID pro = practitioner();
        UUID clinic = clinic("Clinique MedLink Test");
        Instant start = FIRST_SLOT.plus(NEXT_SLOT.getAndIncrement(), ChronoUnit.HOURS);
        UUID appointment = appointment(pro, clinic, start, "CANCELLED");

        alerts.onAppointmentCancelled(load(appointment));

        List<Map<String, Object>> rows = jdbc.queryForList("select practitioner_id, type, appointment_id, message, read_at "
                + "from alerts where appointment_id = ?", appointment);
        assertThat(rows).hasSize(1);
        Map<String, Object> alert = rows.get(0);
        assertThat(alert.get("practitioner_id")).isEqualTo(pro);
        assertThat(alert.get("type")).isEqualTo("APPOINTMENT_CANCELLED");
        assertThat(alert.get("read_at")).isNull();
        assertThat(alert.get("message")).isEqualTo("Rendez-vous annulé : Amel Ben Salah, "
                + tunis(start) + ", Clinique MedLink Test.");
    }

    @Test
    void theMessageGivesTheTimeInTunisWhateverTheServersZone() {
        UUID pro = practitioner();
        UUID appointment = appointment(pro, clinic("Clinique MedLink Sousse"), FIRST_SLOT.minus(1, ChronoUnit.DAYS), "CANCELLED");

        alerts.onAppointmentCancelled(load(appointment));

        // Wednesday 6 January 2027, 08:00 UTC = 09:00 in Tunis (UTC+1, no daylight saving)
        assertThat(message(appointment)).contains("mercredi 6 janvier à 09:00");
    }

    @Test
    void theSameCancellationTwiceGivesOneAlertOnly() {
        UUID pro = practitioner();
        UUID appointment = appointment(pro, clinic("Clinique MedLink Tunis"), FIRST_SLOT.plus(NEXT_SLOT.getAndIncrement(), ChronoUnit.HOURS), "CANCELLED");

        alerts.onAppointmentCancelled(load(appointment));
        alerts.onAppointmentCancelled(load(appointment));

        assertThat(jdbc.queryForObject("select count(*) from alerts where appointment_id = ?", Integer.class, appointment)).isEqualTo(1);
    }

    @Test
    void anAppointmentThatIsNotCancelledGivesNoAlert() {
        UUID pro = practitioner();
        UUID appointment = appointment(pro, clinic("Clinique MedLink Sfax"), FIRST_SLOT.plus(NEXT_SLOT.getAndIncrement(), ChronoUnit.HOURS), "BOOKED");

        alerts.onAppointmentCancelled(load(appointment));

        assertThat(jdbc.queryForObject("select count(*) from alerts where appointment_id = ?", Integer.class, appointment)).isZero();
    }

    @Test
    void theAlertIsInThePractitionersUnreadList() {
        UUID pro = practitioner();
        UUID appointment = appointment(pro, clinic("Clinique MedLink Sousse"), FIRST_SLOT.plus(NEXT_SLOT.getAndIncrement(), ChronoUnit.HOURS), "CANCELLED");

        alerts.onAppointmentCancelled(load(appointment));

        assertThat(alerts.list(pro, true)).extracting(alert -> alert.getAppointmentId()).containsExactly(appointment);
    }

    /** 37.4: a mail that cannot be sent never blocks the cancellation: the alert is kept, nothing is thrown. */
    @Test
    void aMailThatFailsLeavesTheAlertAndThrowsNothing() {
        UUID pro = practitioner();
        UUID appointment = appointment(pro, clinic("Clinique MedLink Tunis"), FIRST_SLOT.plus(NEXT_SLOT.getAndIncrement(), ChronoUnit.HOURS), "CANCELLED");
        doThrow(new MailSendException("smtp down")).when(mails)
                .sendAppointmentCancelled(anyString(), anyString(), anyString(), anyString(), anyString());

        assertThatCode(() -> alerts.onAppointmentCancelled(load(appointment))).doesNotThrowAnyException();

        assertThat(jdbc.queryForObject("select count(*) from alerts where appointment_id = ?", Integer.class, appointment)).isEqualTo(1);
    }

    private Appointment load(UUID appointment) {
        return appointments.findById(appointment).orElseThrow();
    }

    private String message(UUID appointment) {
        return jdbc.queryForObject("select message from alerts where appointment_id = ?", String.class, appointment);
    }

    /** "jeudi 7 janvier à 10:00" for a start in UTC, as the alert writes it. */
    private static String tunis(Instant start) {
        return java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM 'à' HH:mm", java.util.Locale.FRENCH)
                .withZone(java.time.ZoneId.of("Africa/Tunis")).format(start);
    }

    private UUID practitioner() {
        UUID id = UUID.randomUUID();
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", id, "pro-" + id, "pro-" + id + "@example.com");
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Sami', 'Dev', ?)", id, specialty);
        return id;
    }

    private UUID clinic(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, ?, 'Sousse')", id, name);
        return id;
    }

    private UUID appointment(UUID practitioner, UUID clinic, Instant start, String status) {
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", practitioner, clinic);
        UUID patient = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", patient, "patient-" + patient, "patient-" + patient + "@example.com");
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Ben Salah', ?)",
                patient, Date.valueOf("1990-04-12"));
        UUID slot = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, ?)",
                slot, practitioner, clinic, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)),
                "BOOKED".equals(status) ? "BOOKED" : "FREE");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into appointments (id, slot_id, patient_id, booked_by, status) values (?, ?, ?, ?, ?)",
                id, slot, patient, patient, status);
        return id;
    }
}
