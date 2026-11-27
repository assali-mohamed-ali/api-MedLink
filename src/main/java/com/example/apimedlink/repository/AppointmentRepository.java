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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Patients' appointments on slots (MLink-97). */
public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    interface StatusCount {
        AppointmentStatus getStatus();
        long getTotal();
    }

    @Query("""
            select a.status as status, count(a) as total from Appointment a
            where a.slot.clinic.id = :clinicId and a.slot.startsAt >= :from and a.slot.startsAt < :to
            group by a.status""")
    List<StatusCount> countActivity(@Param("clinicId") UUID clinicId, @Param("from") Instant from,
            @Param("to") Instant to);

    /** A page of one patient's appointments with their slot, clinic and practitioner (MLink-108, no N+1). */
    @EntityGraph(attributePaths = {"slot", "slot.clinic", "slot.practitioner"})
    Page<Appointment> findByPatientId(UUID patientId, Pageable pageable);

    /** The appointment a patient cancels, its row locked until the transaction ends: two cancels run in turn (MLink-106). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Appointment a where a.id = :id")
    Optional<Appointment> findForChangeById(@Param("id") UUID id);

    /** MLink-167: whether the patient holds an appointment in {@code status} that overlaps [start, end). */
    @Query("""
            select count(a) > 0 from Appointment a
            where a.patientId = :patientId and a.status = :status
              and a.slot.startsAt < :end and a.slot.endsAt > :start""")
    boolean existsOverlapping(@Param("patientId") UUID patientId, @Param("status") AppointmentStatus status,
            @Param("start") Instant start, @Param("end") Instant end);

    /**
     * MLink-110: the ids of the appointments still to remind, those in {@code status} that start after {@code from}
     * and no later than {@code until}. Only candidates: each one is locked and checked again before its mail
     * (MLink-111, review on #120), since it can be cancelled while the job runs. US-15: a patient who turned reminders
     * off is not a candidate.
     */
    @Query("""
            select a.id from Appointment a
            where a.status = :status and a.reminderSentAt is null
              and a.slot.startsAt > :from and a.slot.startsAt <= :until
              and exists (select 1 from Patient p where p.id = a.patientId and p.notifyAppointmentReminders = true)
            order by a.slot.startsAt""")
    List<UUID> findRemindersDue(@Param("status") AppointmentStatus status, @Param("from") Instant from,
            @Param("until") Instant until);

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
     * The cancelled appointments of these slots with their patient's names, latest cancellation first (MLink-37, 37.7):
     * the agenda shows on a freed slot which appointment was cancelled.
     */
    @Query("""
            select new com.example.apimedlink.repository.BookedAppointment(
                a.slot.id, a.id, p.id, p.firstName, p.lastName)
            from Appointment a join Patient p on p.id = a.patientId
            where a.slot.id in :slotIds and a.status = com.example.apimedlink.model.AppointmentStatus.CANCELLED
            order by a.cancelledAt desc nulls last, a.id""")
    List<BookedAppointment> findCancelledWithPatient(@Param("slotIds") Collection<UUID> slotIds);

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

    /**
     * MLink-32 follow-up: a consultation of this practitioner with this patient has started (a BOOKED appointment whose
     * slot began at or before {@code now}), so a report may be written about it.
     */
    @Query("""
            select count(a) > 0 from Appointment a
            where a.slot.practitioner.id = :practitionerId and a.patientId = :patientId
              and a.status = com.example.apimedlink.model.AppointmentStatus.BOOKED
              and a.slot.startsAt <= :now""")
    boolean existsStartedByPractitionerAndPatient(@Param("practitionerId") UUID practitionerId,
            @Param("patientId") UUID patientId, @Param("now") Instant now);
}
