package com.example.apimedlink.repository;

import com.example.apimedlink.model.Appointment;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Patients' appointments on slots (MLink-97). */
public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    /**
     * Whether the patient is attached to the practitioner (MLink-31): a {@code BOOKED} appointment on one of the
     * practitioner's slots, past or upcoming. A cancelled appointment does not attach (the patient was never seen).
     */
    @Query("""
            select count(a) > 0 from Appointment a
            where a.slot.practitioner.id = :practitionerId and a.patientId = :patientId
              and a.status = com.example.apimedlink.model.AppointmentStatus.BOOKED""")
    boolean existsByPractitionerAndPatient(
            @Param("practitionerId") UUID practitionerId, @Param("patientId") UUID patientId);
}
