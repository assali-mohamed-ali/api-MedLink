package com.example.apimedlink.repository;

import com.example.apimedlink.model.Appointment;
import com.example.apimedlink.model.AppointmentStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
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
}
