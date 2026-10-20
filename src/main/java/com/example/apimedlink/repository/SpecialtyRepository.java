package com.example.apimedlink.repository;

import com.example.apimedlink.model.Specialty;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The medical specialties practitioners hold (MLink-97). */
public interface SpecialtyRepository extends JpaRepository<Specialty, UUID> {}
