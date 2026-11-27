package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * US-08: a patient who books a slot, or moves an appointment, gets a confirmation mail in French with the date, the
 * time (Tunis), the practitioner and the clinic; a refused booking sends nothing. NOT RUN in the session that wrote it
 * (no Docker).
 */
class BookingConfirmationMailFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Booking-Mail-2026!";
    static final String SUBJECT = "Confirmation de votre rendez-vous MedLink";

    final KeycloakConsole console = new KeycloakConsole();
    final Mailbox mailbox = new Mailbox();

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void bookingASlotSendsAConfirmationToThePatient() throws Exception {
        String email = email("confirm-patient");
        Browser patient = patient(email);
        UUID practitioner = practitioner();
        Instant start = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        UUID slot = slot(practitioner, start);

        HttpResponse<String> response = patient.postJson("/api/appointments", "{\"slotId\": \"" + slot + "\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        List<Map<?, ?>> mails = mailbox.to(email, SUBJECT);
        assertThat(mails).hasSize(1);
        assertThat(jdbc.queryForObject("select confirmation_sent_at from appointments where slot_id = ?",
                Timestamp.class, slot)).isNotNull();
        assertThat(mailbox.text(mails.get(0))).contains("Bonjour Amel,").contains("Clinique MedLink Confirmation")
                .contains("Dr Sami Confirme").contains("/patient/rendez-vous");
    }

    @Test
    void movingAnAppointmentSendsAConfirmationForTheNewSlot() throws Exception {
        String email = email("confirm-move");
        Browser patient = patient(email);
        UUID practitioner = practitioner();
        Instant start = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        UUID first = slot(practitioner, start);
        UUID second = slot(practitioner, start.plus(2, ChronoUnit.HOURS));
        assertThat(patient.postJson("/api/appointments", "{\"slotId\": \"" + first + "\"}").statusCode()).isEqualTo(201);
        UUID appointment = jdbc.queryForObject("select id from appointments where slot_id = ?", UUID.class, first);

        HttpResponse<String> response = patient.postJson("/api/appointments/" + appointment + "/reschedule",
                "{\"newSlotId\": \"" + second + "\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(mailbox.to(email, SUBJECT)).hasSize(2);
    }

    @Test
    void aRefusedBookingSendsNothing() throws Exception {
        String email = email("confirm-refused");
        Browser patient = patient(email);
        UUID practitioner = practitioner();
        UUID pastSlot = slot(practitioner, Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS));

        HttpResponse<String> response = patient.postJson("/api/appointments", "{\"slotId\": \"" + pastSlot + "\"}");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(mailbox.to(email, SUBJECT)).isEmpty();
    }

    private Browser patient(String email) throws Exception {
        UUID id = console.createUser(email, PASSWORD);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?) on conflict (id) do nothing", id, email, email);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Ben Salah', ?)",
                id, Date.valueOf("1990-04-12"));
        Browser browser = new Browser();
        assertThat(browser.apiLogin(email, PASSWORD).statusCode()).isEqualTo(200);
        return browser;
    }

    private UUID practitioner() {
        UUID id = UUID.randomUUID();
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", id, "pro-" + id, "pro-" + id + "@example.com");
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Sami', 'Confirme', ?)",
                id, specialty);
        UUID clinic = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, 'Clinique MedLink Confirmation', 'Sousse')", clinic);
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", id, clinic);
        return id;
    }

    private UUID slot(UUID practitioner, Instant start) {
        UUID clinic = jdbc.queryForObject("select clinic_id from practitioner_clinics where practitioner_id = ?",
                UUID.class, practitioner);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, 'FREE')",
                id, practitioner, clinic, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)));
        return id;
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
