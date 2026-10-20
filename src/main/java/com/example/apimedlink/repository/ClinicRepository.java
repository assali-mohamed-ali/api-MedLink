package com.example.apimedlink.repository;

import com.example.apimedlink.model.Clinic;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The MedLink clinics (MLink-97). */
public interface ClinicRepository extends JpaRepository<Clinic, UUID> {}
