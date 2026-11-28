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

/**
 * US-25: the "new result" notice survives an SMTP outage. The mail server is a spy: it fails on demand, then records
 * what it would send. A failed notice stays pending (notice_sent_at null), the next run sends it once and settles it,
 * a later run sends nothing more; a notice older than the retry window is left alone. Unit level on purpose: a
 * functional test cannot take Mailpit down for one request (team rule: outages only).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ExamResultNoticesRetryTest {

    @Autowired
    ExamResultNotices notices;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    JavaMailSender mailSender;

    final List<String> sentTo = new CopyOnWriteArrayList<>();

    @AfterEach
    void restoreMailSender() {
        reset(mailSender);
    }

    @Test
    void aNoticeThatFailsStaysPendingAndTheNextRunSendsItOnce() throws Exception {
        String email = "notice-" + UUID.randomUUID() + "@example.com";
        UUID result = pendingResult(email, Instant.now());
        doThrow(new MailSendException("SMTP down")).when(mailSender).send(any(MimeMessage.class));

        notices.trySend(result);
        notices.sendPending();

        assertThat(noticeSentAt(result)).as("still pending after the outage").isNull();

        reset(mailSender);
        doAnswer(invocation -> {
            MimeMessage message = invocation.getArgument(0);
            sentTo.add(message.getRecipients(Message.RecipientType.TO)[0].toString());
            return null;
        }).when(mailSender).send(any(MimeMessage.class));
        notices.sendPending();
        notices.sendPending();

        assertThat(sentTo).filteredOn(email::equals).hasSize(1);
        assertThat(noticeSentAt(result)).isNotNull();
    }

    @Test
    void aNoticeOlderThanTheRetryWindowIsNotSentAnyMore() {
        String email = "notice-old-" + UUID.randomUUID() + "@example.com";
        UUID result = pendingResult(email, Instant.now().minus(3, ChronoUnit.DAYS));
        doAnswer(invocation -> {
            MimeMessage message = invocation.getArgument(0);
            sentTo.add(message.getRecipients(Message.RecipientType.TO)[0].toString());
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        notices.sendPending();

        assertThat(sentTo).doesNotContain(email);
        assertThat(noticeSentAt(result)).isNull();
    }

    // ---- test data, written straight to the database ---------------------------------------------

    private UUID pendingResult(String email, Instant createdAt) {
        UUID patient = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", patient, email, email);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Ben Salah', ?)",
                patient, Date.valueOf("1990-04-12"));
        UUID practitioner = UUID.randomUUID();
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", practitioner, "pro-" + practitioner,
                "pro-" + practitioner + "@example.com");
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Leila', 'Ben Ali', ?)",
                practitioner, specialty);
        UUID result = UUID.randomUUID();
        jdbc.update("insert into exam_results (id, patient_id, practitioner_id, title_encrypted, type_encrypted, "
                + "content_encrypted, exam_date, created_at) values (?, ?, ?, 'sealed', 'sealed', 'sealed', ?, ?)",
                result, patient, practitioner, Date.valueOf("2026-01-10"), Timestamp.from(createdAt));
        return result;
    }

    private Timestamp noticeSentAt(UUID result) {
        return jdbc.queryForObject("select notice_sent_at from exam_results where id = ?", Timestamp.class, result);
    }
}
