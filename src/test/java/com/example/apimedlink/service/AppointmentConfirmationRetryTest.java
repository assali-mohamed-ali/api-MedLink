package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.example.apimedlink.TestcontainersConfiguration;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AppointmentConfirmationRetryTest {
    @Autowired AppointmentConfirmation confirmations;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean JavaMailSender mailSender;

    @AfterEach
    void restoreMailSender() {
        reset(mailSender);
    }

    @Test
    void anSmtpFailureRemainsPendingAndRecoverySendsOnlyOneConfirmation() throws Exception {
        String email = "confirmation-" + UUID.randomUUID() + "@example.com";
        UUID id = pending(email, "BOOKED");
        doThrow(new MailSendException("SMTP down")).when(mailSender).send(any(MimeMessage.class));
        confirmations.trySend(id);
        assertThat(sentAt(id)).isNull();

        reset(mailSender);
        List<String> ids = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            MimeMessage message = invocation.getArgument(0);
            if (email.equals(message.getRecipients(Message.RecipientType.TO)[0].toString())) {
                ids.add(message.getHeader("Message-ID", null));
            }
            return null;
        }).when(mailSender).send(any(MimeMessage.class));
        confirmations.sendPending();
        confirmations.trySend(id);
        assertThat(ids).containsExactly("<appointment-confirmation-" + id + "@medlink.local>");
        assertThat(sentAt(id)).isNotNull();
    }

    @Test
    void aCancelledAppointmentIsNeverConfirmedByTheRetryJob() throws Exception {
        String email = "confirmation-cancelled-" + UUID.randomUUID() + "@example.com";
        UUID id = pending(email, "CANCELLED");
        List<String> recipients = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            MimeMessage message = invocation.getArgument(0);
            recipients.add(message.getRecipients(Message.RecipientType.TO)[0].toString());
            return null;
        }).when(mailSender).send(any(MimeMessage.class));
        confirmations.trySend(id);
        confirmations.sendPending();
        assertThat(recipients).doesNotContain(email);
        assertThat(sentAt(id)).isNull();
    }

    private Timestamp sentAt(UUID id) {
        return jdbc.queryForObject("select confirmation_sent_at from appointments where id = ?", Timestamp.class, id);
    }

    private UUID pending(String email, String status) {
        UUID patient = UUID.randomUUID(), practitioner = UUID.randomUUID(), specialty = UUID.randomUUID();
        UUID clinic = UUID.randomUUID(), slot = UUID.randomUUID(), appointment = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", patient, email, email);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Test', ?)",
                patient, Date.valueOf("1990-04-12"));
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", practitioner,
                practitioner + "@example.com", practitioner + "@example.com");
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Sami', 'Test', ?)",
                practitioner, specialty);
        jdbc.update("insert into clinics (id, name, city) values (?, 'Synthetic clinic', 'Tunis')", clinic);
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", practitioner, clinic);
        Instant start = Instant.now().plus(3, ChronoUnit.DAYS);
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, ?)",
                slot, practitioner, clinic, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)),
                "BOOKED".equals(status) ? "BOOKED" : "FREE");
        jdbc.update("insert into appointments (id, slot_id, patient_id, booked_by, status, cancelled_at) values (?, ?, ?, ?, ?, ?)",
                appointment, slot, patient, patient, status, "CANCELLED".equals(status) ? Timestamp.from(Instant.now()) : null);
        return appointment;
    }
}
