package com.example.apimedlink.repository;

import com.example.apimedlink.model.Appointment;
import com.example.apimedlink.model.AppointmentStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Patients' appointments on slots (MLink-97). */
public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    /** A page of one patient's appointments with their slot, clinic and practitioner (MLink-108, no N+1). */
    @EntityGraph(attributePaths = {"slot", "slot.clinic", "slot.practitioner"})
    Page<Appointment> findByPatientId(UUID patientId, Pageable pageable);

    /** The appointment a patient cancels, its row locked until the transaction ends: two cancels run in turn (MLink-106). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Appointment a where a.id = :id")
    Optional<Appointment> findForChangeById(@Param("id") UUID id);

    /**
     * MLink-110: the appointments still to remind, those in {@code status} that start after {@code from} and no later
     * than {@code until}, with their slot, clinic and practitioner for the mail.
     */
    @EntityGraph(attributePaths = {"slot", "slot.clinic", "slot.practitioner"})
    @Query("""
            select a from Appointment a
            where a.status = :status and a.reminderSentAt is null
              and a.slot.startsAt > :from and a.slot.startsAt <= :until
            order by a.slot.startsAt""")
    List<Appointment> findRemindersDue(@Param("status") AppointmentStatus status, @Param("from") Instant from,
            @Param("until") Instant until);

    /** MLink-110: the reminder of this appointment went out. Only the first success counts. */
    @Transactional
    @Modifying
    @Query("update Appointment a set a.reminderSentAt = :sentAt where a.id = :id and a.reminderSentAt is null")
    int markReminderSent(@Param("id") UUID id, @Param("sentAt") Instant sentAt);

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
