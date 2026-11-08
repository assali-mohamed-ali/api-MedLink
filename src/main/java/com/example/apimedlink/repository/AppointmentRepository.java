package com.example.apimedlink.repository;

import com.example.apimedlink.model.Appointment;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/** Patients' appointments on slots (MLink-97). */
public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    /** A page of one patient's appointments with their slot, clinic and practitioner (MLink-108, no N+1). */
    @EntityGraph(attributePaths = {"slot", "slot.clinic", "slot.practitioner"})
    Page<Appointment> findByPatientId(UUID patientId, Pageable pageable);
}
