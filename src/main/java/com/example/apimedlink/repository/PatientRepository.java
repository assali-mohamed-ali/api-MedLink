package com.example.apimedlink.repository;

import com.example.apimedlink.model.Patient;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The patient of a user is {@code findById(userId)}: both share the Keycloak id. */
public interface PatientRepository extends JpaRepository<Patient, UUID> {

    /**
     * MLink-167: the patient's row locked until the transaction ends, empty when the user has no patient profile.
     * Two bookings of one patient then run one after the other, so the second sees the first one's appointment.
     */
    @Query(value = "select id from patients where id = :id for update", nativeQuery = true)
    Optional<UUID> lockForBooking(@Param("id") UUID id);
}
