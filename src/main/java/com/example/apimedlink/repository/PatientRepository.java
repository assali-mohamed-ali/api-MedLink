package com.example.apimedlink.repository;

import com.example.apimedlink.model.Patient;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The patient of a user is {@code findById(userId)}: both share the Keycloak id. */
public interface PatientRepository extends JpaRepository<Patient, UUID> {}
