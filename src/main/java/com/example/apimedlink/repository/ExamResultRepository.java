package com.example.apimedlink.repository;

import com.example.apimedlink.model.ExamResult;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** US-13 / US-25: examination results, saved once, read per patient. */
public interface ExamResultRepository extends JpaRepository<ExamResult, UUID> {

    List<ExamResult> findByPatientIdOrderByCreatedAtDesc(UUID patientId);
}
