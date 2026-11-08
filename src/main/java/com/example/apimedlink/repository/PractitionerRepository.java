package com.example.apimedlink.repository;

import com.example.apimedlink.model.Practitioner;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/** The practitioner of a user is {@code findById(userId)}: both share the Keycloak id. */
public interface PractitionerRepository extends JpaRepository<Practitioner, UUID> {

    /** Every practitioner with their specialty and clinics in one query (the slot search form, MLink-163). */
    @EntityGraph(attributePaths = {"specialty", "clinics"})
    List<Practitioner> findAllBy();
}
