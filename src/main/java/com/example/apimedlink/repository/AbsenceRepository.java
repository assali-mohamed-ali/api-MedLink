package com.example.apimedlink.repository;

import com.example.apimedlink.model.Absence;
import com.example.apimedlink.model.Appointment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** US-21: absences, and the slots and appointments an absence covers. */
public interface AbsenceRepository extends JpaRepository<Absence, UUID> {

    List<Absence> findByPractitionerIdOrderByStartsAtDesc(UUID practitionerId);

    Optional<Absence> findByIdAndPractitionerId(UUID id, UUID practitionerId);

    /** Whether one of the practitioner's absences covers any part of {@code [from, to)}. */
    @Query("select count(a) > 0 from Absence a where a.practitionerId = :practitionerId "
            + "and a.startsAt < :to and a.endsAt > :from")
    boolean covers(@Param("practitionerId") UUID practitionerId, @Param("from") Instant from, @Param("to") Instant to);

    /** All overlapping slots, including booked ones that may be released concurrently. */
    @Query("select s.id from Slot s where s.practitioner.id = :practitionerId "
            + "and s.startsAt < :to and s.endsAt > :from")
    List<UUID> overlappingSlotIds(@Param("practitionerId") UUID practitionerId, @Param("from") Instant from,
            @Param("to") Instant to);

    /** The practitioner's BOOKED appointments on slots overlapping {@code [from, to)}, earliest first, with slot and clinic. */
    @Query("select a from Appointment a join fetch a.slot s join fetch s.clinic "
            + "where s.practitioner.id = :practitionerId and a.status = com.example.apimedlink.model.AppointmentStatus.BOOKED "
            + "and s.startsAt < :to and s.endsAt > :from order by s.startsAt, a.id")
    List<Appointment> bookedOverlapping(@Param("practitionerId") UUID practitionerId, @Param("from") Instant from,
            @Param("to") Instant to);

    /** The id of the practitioner's FREE slot in this clinic starting exactly at {@code startsAt}, if any. */
    @Query("select s.id from Slot s where s.practitioner.id = :practitionerId and s.clinic.id = :clinicId "
            + "and s.startsAt = :startsAt and s.status = com.example.apimedlink.model.SlotStatus.FREE")
    Optional<UUID> freeSlotAt(@Param("practitionerId") UUID practitionerId, @Param("clinicId") UUID clinicId,
            @Param("startsAt") Instant startsAt);
}
