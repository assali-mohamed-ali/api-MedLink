package com.example.apimedlink.repository;

import com.example.apimedlink.model.Clinic;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The MedLink clinics (MLink-97). */
public interface ClinicRepository extends JpaRepository<Clinic, UUID> {

    /** The clinics a practitioner works in, by name (MLink-26). */
    @Query("select c from Practitioner p join p.clinics c where p.id = :practitionerId order by c.name")
    List<Clinic> findOfPractitioner(@Param("practitionerId") UUID practitionerId);
}
