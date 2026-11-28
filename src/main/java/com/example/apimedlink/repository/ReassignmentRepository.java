package com.example.apimedlink.repository;

import com.example.apimedlink.model.Reassignment;
import com.example.apimedlink.model.ReassignmentStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** US-29: proposals to move an appointment to another practitioner. */
public interface ReassignmentRepository extends JpaRepository<Reassignment, UUID> {

    /** The proposal, its row locked until the transaction ends: the two acceptances run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reassignment r where r.id = :id")
    Optional<Reassignment> findForChangeById(@Param("id") UUID id);

    boolean existsByAppointmentIdAndStatus(UUID appointmentId, ReassignmentStatus status);

    List<Reassignment> findByStatusOrderByCreatedAtAsc(ReassignmentStatus status);

    @Query("select r from Reassignment r, Appointment a where a.id = r.appointmentId and a.patientId = :patientId")
    Page<Reassignment> forPatient(@Param("patientId") UUID patientId, Pageable page);

    @Query("select r from Reassignment r, Slot s where s.id = r.targetSlotId and s.practitioner.id = :practitionerId")
    Page<Reassignment> forPractitioner(@Param("practitionerId") UUID practitionerId, Pageable page);

    /** The open proposals about the patient's appointments, oldest first. */
    @Query("""
            select r from Reassignment r, Appointment a
            where a.id = r.appointmentId and a.patientId = :patientId
              and r.status = com.example.apimedlink.model.ReassignmentStatus.PENDING
            order by r.createdAt""")
    List<Reassignment> pendingForPatient(@Param("patientId") UUID patientId);

    /** The open proposals that would give the practitioner a patient, oldest first. */
    @Query("""
            select r from Reassignment r, Slot s
            where s.id = r.targetSlotId and s.practitioner.id = :practitionerId
              and r.status = com.example.apimedlink.model.ReassignmentStatus.PENDING
            order by r.createdAt""")
    List<Reassignment> pendingForPractitioner(@Param("practitionerId") UUID practitionerId);
}
