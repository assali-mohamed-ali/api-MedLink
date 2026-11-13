package com.example.apimedlink.service;

import com.example.apimedlink.model.Alert;
import com.example.apimedlink.model.AlertType;
import com.example.apimedlink.model.Appointment;
import com.example.apimedlink.model.AppointmentStatus;
import com.example.apimedlink.model.Slot;
import com.example.apimedlink.repository.AlertRepository;
import com.example.apimedlink.repository.AppointmentRepository;
import com.example.apimedlink.repository.PatientRepository;
import com.example.apimedlink.repository.UserRepository;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A practitioner's alerts (MLink-37). Every query is scoped to the caller: an alert of someone else looks exactly like
 * one that does not exist, so a refusal tells nothing about other practitioners' alerts. Takes and returns entities.
 */
@Service
public class AlertService {

    /** All MedLink clinics are in Tunisia: the alert gives Tunis time, whatever the server's zone. */
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEEE d MMMM 'à' HH:mm", Locale.FRENCH).withZone(ZoneId.of("Africa/Tunis"));

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AlertRepository alerts;
    private final AppointmentRepository appointments;
    private final PatientRepository patients;
    private final UserRepository users;
    private final MailService mail;
    private final Clock clock;

    public AlertService(AlertRepository alerts, AppointmentRepository appointments, PatientRepository patients,
            UserRepository users, MailService mail, Clock clock) {
        this.alerts = alerts;
        this.appointments = appointments;
        this.patients = patients;
        this.users = users;
        this.mail = mail;
        this.clock = clock;
    }

    /**
     * MLink-37 (37.2): an appointment was cancelled, its practitioner gets one alert, kept until read (H-06). Called by
     * the cancel code of US-15 (epic MLink-8), in its transaction: the cancellation and its alert are kept together.
     * Nothing for an appointment that is not cancelled, and one alert only per cancellation (also held by the
     * database), so a cancel processed twice changes nothing. The message names the patient, the day and time in
     * Tunis and the clinic: what the practitioner needs, never medical content.
     */
    @Transactional
    public void onAppointmentCancelled(Appointment cancelled) {
        // Read again here: the caller's entity may come from another transaction (its slot is loaded lazily)
        Appointment appointment = appointments.findById(cancelled.getId()).orElse(null);
        if (appointment == null || appointment.getStatus() != AppointmentStatus.CANCELLED
                || alerts.existsByTypeAndAppointmentId(AlertType.APPOINTMENT_CANCELLED, appointment.getId())) {
            return;
        }
        Slot slot = appointment.getSlot();
        String patient = patients.findById(appointment.getPatientId())
                .map(found -> found.getFirstName() + " " + found.getLastName())
                .orElse("un patient");
        String when = WHEN.format(slot.getStartsAt());
        String clinic = slot.getClinic().getName();
        UUID practitionerId = slot.getPractitioner().getId();
        Alert alert = alerts.save(new Alert(practitionerId, AlertType.APPOINTMENT_CANCELLED, appointment.getId(),
                "Rendez-vous annulé : " + patient + ", " + when + ", " + clinic + "."));
        // 37.4: the mail leaves once the cancellation and its alert are committed, never for a rolled-back one
        String firstName = slot.getPractitioner().getFirstName();
        String to = users.findById(practitionerId).map(user -> user.getEmail()).orElse(null);
        Runnable send = () -> mailQuietly(alert.getId(), to, firstName, patient, when, clinic);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    /**
     * A mail that cannot be sent never blocks the cancellation (37.4): the alert is kept and shows in the bell, the
     * failure is logged by the alert's id only (no address, no name).
     */
    private void mailQuietly(UUID alertId, String to, String firstName, String patient, String when, String clinic) {
        if (to == null || to.isBlank()) {
            log.warn("Cancellation mail not sent for alert {}: the practitioner has no e-mail", alertId);
            return;
        }
        try {
            mail.sendAppointmentCancelled(to, firstName, patient, when, clinic);
        } catch (RuntimeException failed) {
            log.warn("Cancellation mail not sent for alert {}: {}", alertId, failed.getClass().getSimpleName());
        }
    }

    /** The caller's alerts, newest first: the unread ones (the bell), or all of them. */
    @Transactional(readOnly = true)
    public List<Alert> list(UUID practitionerId, boolean unreadOnly) {
        return unreadOnly
                ? alerts.findByPractitionerIdAndReadAtIsNullOrderByCreatedAtDesc(practitionerId)
                : alerts.findByPractitionerIdOrderByCreatedAtDesc(practitionerId);
    }

    /**
     * Marks one of the caller's alerts read. Marking it again changes nothing (the first reading time stays). Another
     * practitioner's alert and an unknown one raise the same {@link AccessDeniedException} (403, audited as a refusal).
     */
    @Transactional
    public void markRead(UUID practitionerId, UUID alertId) {
        Alert alert = alerts.findByIdAndPractitionerId(alertId, practitionerId)
                .orElseThrow(() -> new AccessDeniedException("Not the practitioner's alert"));
        alert.markRead(clock.instant());
    }
}
