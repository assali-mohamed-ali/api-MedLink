package com.example.apimedlink.repository;

import com.example.apimedlink.model.Slot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Practitioners' bookable slots (MLink-97); searched with optional filters (MLink-98) and managed by their
 * practitioner (MLink-26). The application runs with open-in-view off and its mappers run after the
 * transaction: every query below loads the clinic it returns with the slot.
 */
public interface SlotRepository extends JpaRepository<Slot, UUID>, JpaSpecificationExecutor<Slot> {

    /** A page of slots with their practitioner, specialty and clinic loaded in the same query (no N+1). */
    @Override
    @EntityGraph(attributePaths = {"practitioner", "practitioner.specialty", "clinic"})
    Page<Slot> findAll(Specification<Slot> spec, Pageable pageable);

    /** A practitioner's slots starting in {@code [from, to)}, whatever their status or clinic, earliest first. */
    @EntityGraph(attributePaths = {"clinic"})
    @Query("""
            select s from Slot s
            where s.practitioner.id = :practitionerId and s.startsAt >= :from and s.startsAt < :to
            order by s.startsAt, s.id""")
    List<Slot> findAgenda(
            @Param("practitionerId") UUID practitionerId, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * The practitioner's slots that share time with {@code [start, end)}, in any clinic, whatever their status.
     * Half-open, like the database constraint: a slot that ends when the other starts does not overlap.
     */
    @EntityGraph(attributePaths = {"clinic"})
    @Query("""
            select s from Slot s
            where s.practitioner.id = :practitionerId and s.startsAt < :end and s.endsAt > :start
            order by s.startsAt, s.id""")
    List<Slot> findOverlapping(
            @Param("practitionerId") UUID practitionerId, @Param("start") Instant start, @Param("end") Instant end);

    /** The slot of this practitioner, or nothing: a slot of someone else looks the same as one that does not exist. */
    @EntityGraph(attributePaths = {"clinic"})
    Optional<Slot> findByIdAndPractitioner_Id(UUID id, UUID practitionerId);

    /**
     * Closes the slot only if it is still free and holds no active appointment, in one statement, so a patient
     * booking at the same moment can never end up on a closed slot. Returns the number of slots closed (0 or 1).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Slot s set s.status = com.example.apimedlink.model.SlotStatus.CLOSED
            where s.id = :id and s.practitioner.id = :practitionerId
              and s.status = com.example.apimedlink.model.SlotStatus.FREE
              and not exists (select 1 from Appointment a where a.slot.id = s.id
                              and a.status = com.example.apimedlink.model.AppointmentStatus.BOOKED)""")
    int closeIfFree(@Param("id") UUID id, @Param("practitionerId") UUID practitionerId);

    /** Reopens a closed slot that has not started yet (0 or 1 slot). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Slot s set s.status = com.example.apimedlink.model.SlotStatus.FREE
            where s.id = :id and s.practitioner.id = :practitionerId
              and s.status = com.example.apimedlink.model.SlotStatus.CLOSED and s.startsAt > :now""")
    int reopenIfClosed(
            @Param("id") UUID id, @Param("practitionerId") UUID practitionerId, @Param("now") Instant now);
}
