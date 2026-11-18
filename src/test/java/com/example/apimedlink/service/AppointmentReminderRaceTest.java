package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.example.apimedlink.TestcontainersConfiguration;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * MLink-110/111, code owner's review on #120: an appointment cancelled while the reminder job runs gets no reminder.
 * The job reads its list of due appointments first; here the second one is cancelled while the first one's mail is
 * being sent, that is after the list was read and before its own turn. The mail server is a spy that records each
 * message and sends nothing, so the cancel happens at that exact moment, on demand.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AppointmentReminderRaceTest {

    @Autowired
    AppointmentReminderJob job;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    JavaMailSender mailSender;

    /** Recipient address → the message sent to it. */
    final Map<String, MimeMessage> sent = new ConcurrentHashMap<>();

    @Test
    void anAppointmentCancelledWhileTheJobRunsGetsNoReminder() throws Exception {
        Instant now = Instant.now();
        UUID first = booked("first-" + UUID.randomUUID() + "@example.com", now.plus(2, ChronoUnit.HOURS));
        String secondEmail = "second-" + UUID.randomUUID() + "@example.com";
        UUID second = booked(secondEmail, now.plus(3, ChronoUnit.HOURS));
        String firstEmail = email(first);
        doAnswer(invocation -> {
            MimeMessage message = invocation.getArgument(0);
            String to = message.getRecipients(Message.RecipientType.TO)[0].toString();
            sent.put(to, message);
            if (to.equals(firstEmail)) {
                // The patient of the second appointment cancels now, while the job is busy with the first one
                jdbc.update("update appointments set status = 'CANCELLED', cancelled_at = now() where id = ?", second);
            }
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        job.sendDueReminders();

        assertThat(sent).containsKey(firstEmail).doesNotContainKey(secondEmail);
        assertThat(reminderSentAt(first)).isNotNull();
        assertThat(reminderSentAt(second)).isNull();
        // The reminder's Message-ID is the appointment's, the same at every try (at-least-once delivery)
        assertThat(sent.get(firstEmail).getMessageID()).isEqualTo("<appointment-reminder-" + first + "@medlink.local>");
    }

    // ---- test data, written straight to the database ---------------------------------------------

    /** A patient with this address, holding a BOOKED appointment on a slot of their own practitioner. */
    private UUID booked(String email, Instant start) {
        UUID patient = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", patient, email, email);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Ben Salah', ?)",
                patient, Date.valueOf("1990-04-12"));
        UUID practitioner = UUID.randomUUID();
        UUID specialty = UUID.randomUUID();
        UUID clinic = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)",
                practitioner, "pro-" + practitioner, "pro-" + practitioner + "@example.com");
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Rim', 'Dev', ?)",
                practitioner, specialty);
        jdbc.update("insert into clinics (id, name, city) values (?, ?, 'Sfax')", clinic, "Clinique " + clinic);
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", practitioner, clinic);
        UUID slot = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, 'BOOKED')",
                slot, practitioner, clinic, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)));
        UUID appointment = UUID.randomUUID();
        jdbc.update("insert into appointments (id, slot_id, patient_id, booked_by, status) values (?, ?, ?, ?, 'BOOKED')",
                appointment, slot, patient, patient);
        return appointment;
    }

    private String email(UUID appointment) {
        List<String> emails = jdbc.queryForList(
                "select u.email from appointments a join users u on u.id = a.patient_id where a.id = ?", String.class, appointment);
        return emails.get(0);
    }

    private Timestamp reminderSentAt(UUID appointment) {
        return jdbc.queryForObject("select reminder_sent_at from appointments where id = ?", Timestamp.class, appointment);
    }
}
