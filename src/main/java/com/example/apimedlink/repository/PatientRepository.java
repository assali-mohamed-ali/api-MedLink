package com.example.apimedlink.repository;

import com.example.apimedlink.model.Patient;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * US-22: whether the patient is linked to the practitioner: a BOOKED appointment with them, or a consultation
     * report they wrote, or a document they uploaded. Any earlier link counts.
     */
    @Query("""
            select (
                exists (select 1 from Appointment a where a.slot.practitioner.id = :practitionerId
                        and a.patientId = :patientId
                        and a.status = com.example.apimedlink.model.AppointmentStatus.BOOKED)
                or exists (select 1 from ConsultationReport r where r.practitionerId = :practitionerId
                        and r.patientId = :patientId)
                or exists (select 1 from PatientDocument d where d.uploadedBy = :practitionerId
                        and d.patientId = :patientId)
            ) from Patient p where p.id = :patientId""")
    Optional<Boolean> isLinkedTo(@Param("patientId") UUID patientId, @Param("practitionerId") UUID practitionerId);

    /**
     * US-22: the practitioner's linked patients (the rule of {@link #isLinkedTo}, so every one listed can be opened)
     * whose first name, last name, or "first last" / "last first" contains {@code pattern} (already lower-case and
     * escaped with a backslash), or whose record number is {@code number} (null: none typed), by name.
     */
    @Query(value = """
            select p from Patient p
            where (lower(p.firstName) like :pattern escape '\\' or lower(p.lastName) like :pattern escape '\\'
                   or lower(concat(p.firstName, ' ', p.lastName)) like :pattern escape '\\'
                   or lower(concat(p.lastName, ' ', p.firstName)) like :pattern escape '\\'
                   or p.recordNumber = :number)
              and (exists (select 1 from Appointment a where a.slot.practitioner.id = :practitionerId
                           and a.patientId = p.id
                           and a.status = com.example.apimedlink.model.AppointmentStatus.BOOKED)
                or exists (select 1 from ConsultationReport r where r.practitionerId = :practitionerId
                           and r.patientId = p.id)
                or exists (select 1 from PatientDocument d where d.uploadedBy = :practitionerId
                           and d.patientId = p.id))
            order by p.lastName, p.firstName, p.id""",
            countQuery = """
            select count(p) from Patient p
            where (lower(p.firstName) like :pattern escape '\\' or lower(p.lastName) like :pattern escape '\\'
                   or lower(concat(p.firstName, ' ', p.lastName)) like :pattern escape '\\'
                   or lower(concat(p.lastName, ' ', p.firstName)) like :pattern escape '\\'
                   or p.recordNumber = :number)
              and (exists (select 1 from Appointment a where a.slot.practitioner.id = :practitionerId
                           and a.patientId = p.id
                           and a.status = com.example.apimedlink.model.AppointmentStatus.BOOKED)
                or exists (select 1 from ConsultationReport r where r.practitionerId = :practitionerId
                           and r.patientId = p.id)
                or exists (select 1 from PatientDocument d where d.uploadedBy = :practitionerId
                           and d.patientId = p.id))""")
    Page<Patient> searchLinked(@Param("practitionerId") UUID practitionerId, @Param("pattern") String pattern,
            @Param("number") Long number, Pageable page);
}
