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
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * MLink-110 (story MLink-36): every 15 minutes, a reminder mail goes to each patient whose booked appointment starts
 * within the next H-02 hours (24 by default) and who has not had one yet. A cancelled appointment gets none.
 * MLink-111: {@code reminder_sent_at} is set only once the mail is sent, so a patient never gets two; a mail that fails
 * is tried {@value #ATTEMPTS} times, waiting longer each time, then logged and left for the next run.
 * One API instance today: several would need a lock on this job.
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

    public AppointmentReminderJob(AppointmentRepository appointments, PatientRepository patients, MailService mail,
            Clock clock, @Value("${app.appointments.reminder-hours-before}") long reminderHoursBefore,
            @Value("${app.appointments.reminder-retry-backoff}") Duration retryBackoff) {
        this.appointments = appointments;
        this.patients = patients;
        this.mail = mail;
        this.clock = clock;
        this.remindBefore = Duration.ofHours(reminderHoursBefore);
        this.retryBackoff = retryBackoff;
    }

    /**
     * Sends the reminders due now; returns how many went out. The first run waits one interval, so a restart never
     * floods the mail server; {@code synchronized}: two runs never overlap.
     */
    @Scheduled(initialDelayString = "${app.appointments.reminder-interval}",
            fixedDelayString = "${app.appointments.reminder-interval}")
    public synchronized int sendDueReminders() {
        Instant now = clock.instant();
        List<Appointment> due = appointments.findRemindersDue(AppointmentStatus.BOOKED, now, now.plus(remindBefore));
        if (due.isEmpty()) {
            return 0;
        }
        Map<UUID, Patient> holders = patients
                .findAllById(due.stream().map(Appointment::getPatientId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Patient::getId, Function.identity()));
        int sent = 0;
        for (Appointment appointment : due) {
            Patient holder = holders.get(appointment.getPatientId());
            if (holder == null || holder.getEmail() == null) {
                continue;
            }
            if (send(appointment, holder)) {
                sent += appointments.markReminderSent(appointment.getId(), clock.instant());
            }
        }
        return sent;
    }

    /**
     * Up to {@value #ATTEMPTS} tries, waiting the backoff, then twice it. False when all failed: logged with the
     * appointment id only, never the address or anything about the appointment.
     */
    private boolean send(Appointment appointment, Patient holder) {
        Slot slot = appointment.getSlot();
        Practitioner practitioner = slot.getPractitioner();
        String who = "Dr " + practitioner.getFirstName() + " " + practitioner.getLastName();
        for (int attempt = 1; ; attempt++) {
            try {
                mail.sendAppointmentReminder(holder.getEmail(), holder.getFirstName(), slot.getClinic().getName(), who,
                        slot.getStartsAt());
                return true;
            } catch (MailException e) {
                if (attempt == ATTEMPTS) {
                    log.warn("Reminder for appointment {} not sent after {} attempts ({}); the next run tries again",
                            appointment.getId(), ATTEMPTS, e.getClass().getSimpleName());
                    return false;
                }
                if (!pause(retryBackoff.multipliedBy(1L << (attempt - 1)))) {
                    return false;
                }
            }
        }
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
