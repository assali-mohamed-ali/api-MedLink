package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.Appointment;
import com.example.apimedlink.model.Slot;
import com.example.apimedlink.model.SlotStatus;
import com.example.apimedlink.repository.AppointmentRepository;
import com.example.apimedlink.repository.PatientRepository;
import com.example.apimedlink.repository.SlotRepository;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A patient's appointments (epic MLink-8). Takes and returns entities and plain values, never a DTO. */
@Service
public class AppointmentService {

    static final String SLOT_TAKEN = "Ce créneau n'est plus disponible. Choisissez-en un autre.";

    private final AppointmentRepository appointments;
    private final SlotRepository slots;
    private final PatientRepository patients;

    public AppointmentService(AppointmentRepository appointments, SlotRepository slots, PatientRepository patients) {
        this.appointments = appointments;
        this.slots = slots;
        this.patients = patients;
    }

    /**
     * MLink-102: the patient books a free slot that has not started, for themselves ({@code patient_id} =
     * {@code booked_by} = the caller). One transaction: the slot row is locked, the appointment is created and the
     * slot becomes BOOKED, so a second booking of the same slot waits, then sees it taken.
     */
    @Transactional
    public Appointment book(UUID patientId, UUID slotId) {
        // A patient account made outside registration (e.g. in the Keycloak console) has no profile to book with
        if (!patients.existsById(patientId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PATIENT_PROFILE_REQUIRED",
                    "Complétez votre profil patient avant de réserver.");
        }
        Slot slot = slots.findForBookingById(slotId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Ce créneau n'existe pas."));
        if (slot.getStatus() != SlotStatus.FREE || !slot.getStartsAt().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "SLOT_TAKEN", SLOT_TAKEN);
        }
        slot.setStatus(SlotStatus.BOOKED);
        Appointment appointment = appointments.save(new Appointment(slot, patientId, patientId));
        // Loaded here, inside the transaction: the controller maps them after it has ended
        Hibernate.initialize(slot.getPractitioner());
        Hibernate.initialize(slot.getClinic());
        return appointment;
    }
}
