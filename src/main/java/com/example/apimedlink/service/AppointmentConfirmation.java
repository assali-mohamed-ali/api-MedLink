package com.example.apimedlink.service;

import com.example.apimedlink.model.Appointment;
import com.example.apimedlink.model.AppointmentStatus;
import com.example.apimedlink.model.Patient;
import com.example.apimedlink.model.Slot;
import com.example.apimedlink.repository.AppointmentRepository;
import com.example.apimedlink.repository.PatientRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** US-08: durable, after-commit booking confirmation. SMTP delivery is at least once, with a stable Message-ID. */
@Service
public class AppointmentConfirmation {

    private static final Logger log = LoggerFactory.getLogger(AppointmentConfirmation.class);
    private final MailService mail;
    private final AppointmentRepository appointments;
    private final PatientRepository patients;
    private final TransactionTemplate transaction;
    private final Duration maxAge;

    public AppointmentConfirmation(MailService mail, AppointmentRepository appointments, PatientRepository patients,
            PlatformTransactionManager transactions,
            @Value("${app.appointments.confirmation-max-age:2d}") Duration maxAge) {
        this.mail = mail;
        this.appointments = appointments;
        this.patients = patients;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.maxAge = maxAge;
    }

    /** A rolled-back booking never sends a confirmation. The scheduled job recovers a missed after-commit callback. */
    public void onBooked(Appointment appointment) {
        UUID id = appointment.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                trySend(id);
            }
        });
    }

    public void trySend(UUID id) {
        try {
            transaction.execute(status -> settle(id));
        } catch (RuntimeException failed) {
            log.warn("Confirmation of appointment {} not sent ({}); retry pending", id,
                    failed.getClass().getSimpleName());
        }
    }

    @Scheduled(initialDelayString = "${app.appointments.confirmation-interval:1m}",
            fixedDelayString = "${app.appointments.confirmation-interval:1m}")
    public void sendPending() {
        Instant now = Instant.now();
        for (UUID id : appointments.findPendingConfirmations(now, now.minus(maxAge))) {
            trySend(id);
        }
    }

    private boolean settle(UUID id) {
        if (appointments.lockPendingConfirmation(id).isEmpty()) {
            return false;
        }
        Appointment appointment = appointments.findById(id).orElseThrow();
        Slot slot = appointment.getSlot();
        if (appointment.getStatus() != AppointmentStatus.BOOKED || !slot.getStartsAt().isAfter(Instant.now())) {
            return false;
        }
        Patient patient = patients.findById(appointment.getPatientId()).orElseThrow();
        String practitioner = "Dr " + slot.getPractitioner().getFirstName() + " " + slot.getPractitioner().getLastName();
        mail.sendAppointmentConfirmation(patient.getEmail(), patient.getFirstName(), slot.getClinic().getName(),
                practitioner, slot.getStartsAt(), id);
        appointment.setConfirmationSentAt(Instant.now());
        return true;
    }
}
