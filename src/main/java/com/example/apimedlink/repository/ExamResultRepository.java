package com.example.apimedlink.repository;

import com.example.apimedlink.model.ExamResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** US-13 / US-25: examination results, saved once, read per patient. */
public interface ExamResultRepository extends JpaRepository<ExamResult, UUID> {

    List<ExamResult> findByPatientIdOrderByCreatedAtDesc(UUID patientId);

    List<ExamResult> findByPractitionerIdAndPatientIdOrderByCreatedAtDesc(UUID practitionerId, UUID patientId);

    /** Marks the patient's unseen results as seen. Native: the entity is immutable for Hibernate. */
    @Modifying
    @Query(value = "update exam_results set read_at = :now where patient_id = :patientId and read_at is null",
            nativeQuery = true)
    int markRead(@Param("patientId") UUID patientId, @Param("now") Instant now);
}
