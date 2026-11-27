package com.example.apimedlink.service;

import com.example.apimedlink.model.Appointment;
import com.example.apimedlink.model.Slot;
import com.example.apimedlink.repository.PatientRepository;
import com.example.apimedlink.repository.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * US-08: the patient gets a confirmation mail for a booking or a move. Everything the mail says is read inside the
 * caller's transaction (the slot is loaded lazily); the mail leaves only once that transaction is committed, never for a
 * rolled-back booking. A mail that cannot be sent never blocks the booking: it is logged by appointment id only (no
 * address, no name).
 */
@Service
public class AppointmentConfirmation {

    private static final Logger log = LoggerFactory.getLogger(AppointmentConfirmation.class);

    private final MailService mail;
    private final UserRepository users;
    private final PatientRepository patients;

    public AppointmentConfirmation(MailService mail, UserRepository users, PatientRepository patients) {
        this.mail = mail;
        this.users = users;
        this.patients = patients;
    }

    /** Call inside the booking's transaction, with the new appointment. */
    public void onBooked(Appointment appointment) {
        UUID id = appointment.getId();
        Slot slot = appointment.getSlot();
        String to = users.findById(appointment.getPatientId()).map(user -> user.getEmail()).orElse(null);
        String firstName = patients.findById(appointment.getPatientId()).map(patient -> patient.getFirstName()).orElse(null);
        String clinic = slot.getClinic().getName();
        String practitioner = "Dr " + slot.getPractitioner().getFirstName() + " " + slot.getPractitioner().getLastName();
        Instant startsAt = slot.getStartsAt();
        afterCommit(() -> {
            if (to == null || to.isBlank()) {
                log.warn("Confirmation mail not sent for appointment {}: no e-mail", id);
                return;
            }
            try {
                mail.sendAppointmentConfirmation(to, firstName, clinic, practitioner, startsAt, id);
            } catch (RuntimeException failed) {
                log.warn("Confirmation mail not sent for appointment {}: {}", id, failed.getClass().getSimpleName());
            }
        });
    }

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
}
