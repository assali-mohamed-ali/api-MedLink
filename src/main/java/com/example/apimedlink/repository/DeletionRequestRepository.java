package com.example.apimedlink.repository;

import com.example.apimedlink.model.DeletionRequest;
import com.example.apimedlink.model.DeletionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** US-16: the patients' deletion requests, looked up by the patient's id. */
public interface DeletionRequestRepository extends JpaRepository<DeletionRequest, UUID> {

    List<DeletionRequest> findByStatusOrderByRequestedAtAsc(DeletionStatus status);

    /** The start of the patient's latest consultation that took place (a BOOKED appointment already started), or null. */
    @Query("""
            select max(s.startsAt) from Appointment a join a.slot s
            where a.patientId = :patientId and a.status = com.example.apimedlink.model.AppointmentStatus.BOOKED
              and s.startsAt <= :now""")
    Instant lastAppointmentStart(@Param("patientId") UUID patientId, @Param("now") Instant now);

    /** When the latest consultation report about the patient was written, or null. */
    @Query("select max(r.createdAt) from ConsultationReport r where r.patientId = :patientId")
    Instant lastReportAt(@Param("patientId") UUID patientId);
}
