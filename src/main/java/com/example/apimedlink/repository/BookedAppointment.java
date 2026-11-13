package com.example.apimedlink.repository;

import java.util.UUID;

/**
 * The active appointment on a slot with the names of its patient, as the practitioner's agenda needs them
 * (MLink-26). Plain values: nothing else of the patient is read.
 */
public record BookedAppointment(UUID slotId, UUID appointmentId, UUID patientId, String firstName, String lastName) {}
