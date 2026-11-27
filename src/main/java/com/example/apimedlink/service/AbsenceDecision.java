package com.example.apimedlink.service;

import com.example.apimedlink.model.Appointment;
import java.util.UUID;

/** US-21: what the practitioner decided for one appointment. {@code moved} is the new appointment, null for a cancel. */
public record AbsenceDecision(Action action, UUID appointmentId, Appointment moved) {

    public enum Action {
        CANCEL,
        POSTPONE
    }
}
