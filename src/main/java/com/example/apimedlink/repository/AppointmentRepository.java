package com.example.apimedlink.repository;

import com.example.apimedlink.model.Appointment;
import com.example.apimedlink.model.AppointmentStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Patients' appointments on slots (MLink-97). */
public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    boolean existsBySlot_IdAndStatus(UUID slotId, AppointmentStatus status);

    /**
     * The appointments with the given status on these slots, with their patient's names, in one query. The
     * patient is joined on {@code patientId} (a plain column, not a relation) and only ids and names are read.
     */
    @Query("""
            select new com.example.apimedlink.repository.BookedAppointment(
                a.slot.id, a.id, p.id, p.firstName, p.lastName)
            from Appointment a join Patient p on p.id = a.patientId
            where a.slot.id in :slotIds and a.status = :status""")
    List<BookedAppointment> findWithPatient(
            @Param("slotIds") Collection<UUID> slotIds, @Param("status") AppointmentStatus status);

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
