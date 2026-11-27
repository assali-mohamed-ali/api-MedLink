package com.example.apimedlink.service;

import com.example.apimedlink.model.ExamResult;
import com.example.apimedlink.model.Patient;
import com.example.apimedlink.repository.ExamResultRepository;
import com.example.apimedlink.repository.PatientRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * US-25: the patient is told by mail that a new result is in their space, once the publication has committed. The mail
 * carries no medical content (no title, type, author or text), only a link to the login.
 *
 * <p>Durable, like the reminders (MLink-111): {@code exam_results.notice_sent_at} is null until the notice is settled.
 * The publishing request tries at once, after its commit; a failure (SMTP down) leaves the result pending and this
 * job tries again every {@code app.results.notice-interval}, for {@code app.results.notice-max-age}. Each try locks the
 * row ({@code skip locked}: a second instance never sends the same notice at the same time), checks it is still
 * pending, reads the patient's preference again, sends, and settles in the same transaction. A patient who turned the
 * notice off has it settled without a mail. Delivery is at least once with one Message-ID per result, so a mail sent
 * whose settle failed and is sent again is seen once. Only ids reach the log.
 */
@Component
public class ExamResultNotices {

    private static final Logger log = LoggerFactory.getLogger(ExamResultNotices.class);

    private final ExamResultRepository results;
    private final PatientRepository patients;
    private final MailService mail;
    private final TransactionTemplate transaction;
    private final Duration maxAge;

    public ExamResultNotices(ExamResultRepository results, PatientRepository patients, MailService mail,
            PlatformTransactionManager transactions,
            @Value("${app.results.notice-max-age:2d}") Duration maxAge) {
        this.results = results;
        this.patients = patients;
        this.mail = mail;
        this.transaction = new TransactionTemplate(transactions);
        this.maxAge = maxAge;
    }

    /** The publication's own try, after its commit. Never throws: a failure waits for the job. */
    public void trySend(UUID resultId) {
        try {
            transaction.execute(status -> settle(resultId));
        } catch (RuntimeException failed) {
            log.warn("Notice of result {} not sent ({}); the next run tries again", resultId,
                    failed.getClass().getSimpleName());
        }
    }

    /** The pending notices, oldest first; returns how many were settled (mailed or not wanted). */
    @Scheduled(initialDelayString = "${app.results.notice-interval:1m}",
            fixedDelayString = "${app.results.notice-interval:1m}")
    public synchronized int sendPending() {
        List<UUID> pending = results.findPendingNotices(Instant.now().minus(maxAge));
        int settled = 0;
        for (UUID id : pending) {
            try {
                if (Boolean.TRUE.equals(transaction.execute(status -> settle(id)))) {
                    settled++;
                }
            } catch (MailException failed) {
                log.warn("Notice of result {} not sent ({}); the next run tries again", id,
                        failed.getClass().getSimpleName());
            } catch (RuntimeException failed) {
                log.warn("Notice of result {} not settled ({}); the next run tries again", id,
                        failed.getClass().getSimpleName());
            }
        }
        return settled;
    }

    /** Inside the transaction: lock, check, mail if wanted, settle. False when another run had it or it was settled. */
    private Boolean settle(UUID resultId) {
        if (results.lockPendingNotice(resultId).isEmpty()) {
            return false;
        }
        ExamResult result = results.findById(resultId).orElse(null);
        Patient patient = result == null ? null : patients.findById(result.getPatientId()).orElse(null);
        if (patient != null && patient.isNotifyExamResults() && patient.getEmail() != null) {
            mail.sendNewExamResult(patient.getEmail(), patient.getFirstName(), resultId);
        }
        results.settleNotice(resultId, Instant.now());
        return true;
    }
}
