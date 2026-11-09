package com.example.apimedlink.repository;

import com.example.apimedlink.model.Appointment;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Patients' appointments on slots (MLink-97). */
public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {}
