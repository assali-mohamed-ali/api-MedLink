package com.example.apimedlink.repository;

import com.example.apimedlink.model.Practitioner;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The practitioner of a user is {@code findById(userId)}: both share the Keycloak id. */
public interface PractitionerRepository extends JpaRepository<Practitioner, UUID> {}
