package com.example.apimedlink.service;

import com.example.apimedlink.model.Appointment;
import com.example.apimedlink.model.AppointmentStatus;
import com.example.apimedlink.model.Patient;
import com.example.apimedlink.model.Practitioner;
import com.example.apimedlink.model.Slot;
import com.example.apimedlink.repository.AppointmentRepository;
import com.example.apimedlink.repository.PatientRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MLink-110 (story MLink-36): every 15 minutes, a reminder mail goes to each patient whose booked appointment starts
 * within the next H-02 hours (24 by default) and who has not had one yet. A cancelled appointment gets none.
 *
 * <p>MLink-111, review on #120. Each reminder is sent inside a short transaction that first locks the appointment's
 * row (the lock a cancel takes) and checks it again: still BOOKED, not reminded yet, still in the window. So a cancel
 * and a reminder run one after the other, never across each other, and a second API instance waits on the lock, then
 * finds the reminder already marked. {@code reminder_sent_at} is set in that same transaction, after the mail.
 *
 * <p>Delivery is <b>at least once</b>: SMTP cannot take part in the transaction, so if the mail server accepted the
 * message and the marker then fails to commit (a crash, the database), the next run sends it again. The two copies
 * carry the same Message-ID (one per appointment), so mail systems see one message. A mail that fails is tried
 * {@value #ATTEMPTS} times, waiting longer each time, then logged by appointment id and left for the next run.
 */
@Component
public class AppointmentReminderJob {

    private static final Logger log = LoggerFactory.getLogger(AppointmentReminderJob.class);

    /** Tries per reminder and run: the first one plus two retries. */
    static final int ATTEMPTS = 3;

    private final AppointmentRepository appointments;
    private final PatientRepository patients;
    private final MailService mail;
    private final Clock clock;
    private final Duration remindBefore;
    private final Duration retryBackoff;
    private final TransactionTemplate transaction;

    public AppointmentReminderJob(AppointmentRepository appointments, PatientRepository patients, MailService mail,
            Clock clock, PlatformTransactionManager transactions,
            @Value("${app.appointments.reminder-hours-before}") long reminderHoursBefore,
            @Value("${app.appointments.reminder-retry-backoff}") Duration retryBackoff) {
        this.appointments = appointments;
        this.patients = patients;
        this.mail = mail;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.remindBefore = Duration.ofHours(reminderHoursBefore);
        this.retryBackoff = retryBackoff;
    }

    /**
     * Sends the reminders due now; returns how many went out. The first run waits one interval, so a restart never
     * floods the mail server; {@code synchronized}: two runs of this instance never overlap.
     */
    @Scheduled(initialDelayString = "${app.appointments.reminder-interval}",
            fixedDelayString = "${app.appointments.reminder-interval}")
    public synchronized int sendDueReminders() {
        Instant now = clock.instant();
        List<UUID> due = appointments.findRemindersDue(AppointmentStatus.BOOKED, now, now.plus(remindBefore));
        int sent = 0;
        for (UUID id : due) {
            for (int attempt = 1; ; attempt++) {
                AtomicBoolean mailed = new AtomicBoolean();
                try {
                    if (Boolean.TRUE.equals(transaction.execute(status -> sendIfStillDue(id, mailed)))) {
                        sent++;
                    }
                    break;
                } catch (MailException e) {
                    if (attempt == ATTEMPTS) {
                        // The id only: never the address or anything about the appointment
                        log.warn("Reminder for appointment {} not sent after {} attempts ({}); the next run tries again",
                                id, ATTEMPTS, e.getClass().getSimpleName());
                        break;
                    }
                    if (!pause(retryBackoff.multipliedBy(1L << (attempt - 1)))) {
                        return sent;
                    }
                } catch (RuntimeException e) {
                    if (mailed.get()) {
                        log.warn("Reminder for appointment {} was sent but not marked ({}); the next run sends it again, "
                                + "with the same Message-ID", id, e.getClass().getSimpleName());
                    } else {
                        log.warn("Reminder for appointment {} not sent ({}); the next run tries again",
                                id, e.getClass().getSimpleName());
                    }
                    break;
                }
            }
        }
        return sent;
    }

    /**
     * Inside the transaction: lock the appointment, check it again, send, mark. True when the mail went out. A
     * {@link MailException} rolls the transaction back and leaves the row as it was.
     */
    private Boolean sendIfStillDue(UUID id, AtomicBoolean mailed) {
        Appointment appointment = appointments.findForChangeById(id).orElse(null);
        Instant now = clock.instant();
        if (appointment == null || appointment.getStatus() != AppointmentStatus.BOOKED
                || appointment.getReminderSentAt() != null) {
            return false;
        }
        Slot slot = appointment.getSlot();
        if (!slot.getStartsAt().isAfter(now) || slot.getStartsAt().isAfter(now.plus(remindBefore))) {
            return false;
        }
        Patient holder = patients.findById(appointment.getPatientId()).orElse(null);
        if (holder == null || holder.getEmail() == null) {
            return false;
        }
        Practitioner practitioner = slot.getPractitioner();
        mail.sendAppointmentReminder(holder.getEmail(), holder.getFirstName(), slot.getClinic().getName(),
                "Dr " + practitioner.getFirstName() + " " + practitioner.getLastName(), slot.getStartsAt(),
                appointment.getId());
        mailed.set(true);
        appointment.setReminderSentAt(clock.instant());
        return true;
    }

    /** False when the application stops meanwhile: the reminder waits for the next start. */
    private static boolean pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
