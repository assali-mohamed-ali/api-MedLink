package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.service.AppointmentReminderJob;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MLink-110 (story MLink-36): a patient gets a reminder mail within 24 hours of a booked appointment (H-02); a
 * cancelled appointment or one further away gets none. The job runs every 15 minutes: the tests call it directly and
 * read the mails in Mailpit.
 */
class AppointmentReminderFunctionalTest extends FunctionalTestBase {

    static final String SUBJECT = "Rappel de votre rendez-vous MedLink";
    static final ZoneId TUNIS = ZoneId.of("Africa/Tunis");

    final Mailbox mailbox = new Mailbox();

    @Autowired
    AppointmentReminderJob job;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aBookedAppointmentWithinTwentyFourHoursGetsOneReminderWithWhereWithWhomAndWhen() {
        Instant start = CLOCK.instant().plus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
        String email = uniqueEmail("reminder");
        UUID appointment = appointment(patient(email, "Amel"),
                slot(practitioner("Rim", "Jaziri", "Cardiologie (rappel)"), clinic("Clinique Rappel Sfax"), start),
                "BOOKED");

        job.sendDueReminders();

        List<Map<?, ?>> mails = mailbox.to(email, SUBJECT);
        assertThat(mails).hasSize(1);
        ZonedDateTime local = start.atZone(TUNIS);
        assertThat(mailbox.text(mails.get(0)))
                .contains("Bonjour Amel,")
                .contains("Date : " + local.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)))
                .contains("Heure : " + local.format(DateTimeFormatter.ofPattern("HH'h'mm")) + " (heure de Tunisie)")
                .contains("Praticien : Dr Rim Jaziri")
                .contains("Clinique : Clinique Rappel Sfax")
                .contains("/patient/rendez-vous");
        assertThat(jdbc.queryForObject("select reminder_sent_at from appointments where id = ?", Timestamp.class,
                appointment)).isNotNull();
    }

    @Test
    void aCancelledAppointmentGetsNoReminder() {
        Instant start = CLOCK.instant().plus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
        String email = uniqueEmail("reminder-cancelled");
        UUID appointment = appointment(patient(email, "Sami"),
                slot(practitioner("Omar", "Gharbi", "Pédiatrie (rappel annulé)"), clinic("Clinique Rappel Sousse"), start),
                "CANCELLED");

        job.sendDueReminders();

        assertThat(mailbox.to(email, SUBJECT)).isEmpty();
        assertThat(jdbc.queryForObject("select reminder_sent_at from appointments where id = ?", Timestamp.class,
                appointment)).isNull();
    }

    @Test
    void aBookedAppointmentMoreThanTwentyFourHoursAwayGetsNoReminderYet() {
        Instant start = CLOCK.instant().plus(30, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
        String email = uniqueEmail("reminder-later");
        UUID appointment = appointment(patient(email, "Nadia"),
                slot(practitioner("Hela", "Mansour", "Dermatologie (rappel plus tard)"), clinic("Clinique Rappel Tunis"),
                        start),
                "BOOKED");

        job.sendDueReminders();

        assertThat(mailbox.to(email, SUBJECT)).isEmpty();
        assertThat(jdbc.queryForObject("select reminder_sent_at from appointments where id = ?", Timestamp.class,
                appointment)).isNull();
    }

    // ---- test data, written straight to the database ---------------------------------------------

    /** A patient as registration leaves them: a users row and a patients row with the first name. */
    private UUID patient(String email, String firstName) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", id, email, email);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, ?, 'Ben Salah', ?)",
                id, firstName, Date.valueOf("1990-04-12"));
        return id;
    }

    /** Specialty names are unique: each test uses its own. */
    private UUID practitioner(String firstName, String lastName, String specialty) {
        UUID specialtyId = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialtyId, specialty);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)",
                id, "practitioner-" + id, "practitioner-" + id + "@example.com");
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, ?, ?, ?)",
                id, firstName, lastName, specialtyId);
        return id;
    }

    private UUID clinic(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, ?, ?)", id, name, name);
        return id;
    }

    /** The practitioner works in the clinic, then a booked slot of 30 minutes. */
    private UUID slot(UUID practitioner, UUID clinic, Instant start) {
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?) on conflict do nothing",
                practitioner, clinic);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, 'BOOKED')",
                id, practitioner, clinic, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)));
        return id;
    }

    private UUID appointment(UUID patient, UUID slot, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into appointments (id, slot_id, patient_id, booked_by, status) values (?, ?, ?, ?, ?)",
                id, slot, patient, patient, status);
        if ("CANCELLED".equals(status)) {
            jdbc.update("update appointments set cancelled_at = now() where id = ?", id);
        }
        return id;
    }
}
