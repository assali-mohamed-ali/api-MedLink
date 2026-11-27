package com.example.apimedlink.repository;

import com.example.apimedlink.model.Practitioner;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/** The practitioner of a user is {@code findById(userId)}: both share the Keycloak id. */
public interface PractitionerRepository extends JpaRepository<Practitioner, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Practitioner p where p.id = :id")
    Optional<Practitioner> lockSchedule(@Param("id") UUID id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update practitioners set booking_enabled = :enabled where id = :id", nativeQuery = true)
    int setBookingEnabled(@Param("id") UUID id, @Param("enabled") boolean enabled);

    /** Every practitioner with their specialty and clinics in one query (the slot search form, MLink-163). */
    @EntityGraph(attributePaths = {"specialty", "clinics"})
    List<Practitioner> findAllBy();
}
