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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
        String patient = patientName(appointment.getPatientId());
        String when = WHEN.format(slot.getStartsAt());
        String clinic = slot.getClinic().getName();
        UUID practitionerId = slot.getPractitioner().getId();
        Alert alert = alerts.save(new Alert(practitionerId, AlertType.APPOINTMENT_CANCELLED, appointment.getId(),
                "Rendez-vous annulé : " + patient + ", " + when + ", " + clinic + "."));
        // 37.4: the mail leaves once the cancellation and its alert are committed, never for a rolled-back one
        String firstName = slot.getPractitioner().getFirstName();
        String to = users.findById(practitionerId).map(user -> user.getEmail()).orElse(null);
        afterCommit(() -> mailQuietly(alert.getId(), to,
                () -> mail.sendAppointmentCancelled(to, firstName, patient, when, clinic)));
    }

    /**
     * A move (MLink-107) is not a cancellation: the practitioner of the old slot gets APPOINTMENT_MOVED, "reporté" from
     * the old date (and its clinic, their slot that is free again) to the new date, and a mail that says so. Only the new
     * date: never the practitioner or the clinic the patient chose. Called by the move, in its transaction; a move
     * processed twice changes nothing.
     */
    @Transactional
    public void onAppointmentMoved(Appointment cancelled, Appointment booked) {
        // Read again here, as for a cancellation: the slots are loaded lazily
        Appointment old = appointments.findById(cancelled.getId()).orElse(null);
        Appointment moved = appointments.findById(booked.getId()).orElse(null);
        if (old == null || moved == null || old.getStatus() != AppointmentStatus.CANCELLED
                || alerts.existsByTypeAndAppointmentId(AlertType.APPOINTMENT_MOVED, old.getId())) {
            return;
        }
        Slot from = old.getSlot();
        String patient = patientName(old.getPatientId());
        String fromWhen = WHEN.format(from.getStartsAt());
        String fromClinic = from.getClinic().getName();
        String toWhen = WHEN.format(moved.getSlot().getStartsAt());
        UUID practitionerId = from.getPractitioner().getId();
        Alert alert = alerts.save(new Alert(practitionerId, AlertType.APPOINTMENT_MOVED, old.getId(),
                "Rendez-vous reporté : " + patient + ", du " + fromWhen + " (" + fromClinic + ") au " + toWhen + "."));
        String firstName = from.getPractitioner().getFirstName();
        String to = users.findById(practitionerId).map(user -> user.getEmail()).orElse(null);
        afterCommit(() -> mailQuietly(alert.getId(), to,
                () -> mail.sendAppointmentMoved(to, firstName, patient, fromWhen, fromClinic, toWhen)));
    }

    /** "Amel Ben Salah", or "un patient" for a profile that is gone. */
    private String patientName(UUID patientId) {
        return patients.findById(patientId).map(found -> found.getFirstName() + " " + found.getLastName()).orElse("un patient");
    }

    /** Runs once the caller's transaction is committed, never for a rolled-back one; at once without a transaction. */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /**
     * A mail that cannot be sent never blocks the cancellation or the move (37.4): the alert is kept and shows in the
     * bell, the failure is logged by the alert's id only (no address, no name).
     */
    private void mailQuietly(UUID alertId, String to, Runnable send) {
        if (to == null || to.isBlank()) {
            log.warn("Alert mail not sent for alert {}: the practitioner has no e-mail", alertId);
            return;
        }
        try {
            send.run();
        } catch (RuntimeException failed) {
            log.warn("Alert mail not sent for alert {}: {}", alertId, failed.getClass().getSimpleName());
        }
    }

    /** Alerts per page when none is asked (what the bell reads every minute); {@link #MAX_PAGE_SIZE} at most. */
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;

    /** Newest first, then by id: alerts of the same instant keep one order, so pages never repeat or skip one. */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    /** The first page of the caller's alerts: the unread ones (the bell), or all of them. */
    public List<Alert> list(UUID practitionerId, boolean unreadOnly) {
        return list(practitionerId, unreadOnly, 0, DEFAULT_PAGE_SIZE);
    }

    /**
     * One page of the caller's alerts, newest first (review on #125): never the whole backlog in one answer, since the
     * bell polls every minute and unread alerts are kept until read. The controller checks the page and size.
     */
    @Transactional(readOnly = true)
    public List<Alert> list(UUID practitionerId, boolean unreadOnly, int page, int size) {
        PageRequest request = PageRequest.of(page, size, NEWEST_FIRST);
        return unreadOnly
                ? alerts.findByPractitionerIdAndReadAtIsNull(practitionerId, request)
                : alerts.findByPractitionerId(practitionerId, request);
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
