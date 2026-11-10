package com.example.apimedlink.repository;

import com.example.apimedlink.model.ConsultationReport;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Consultation reports (MLink-32): saved once, read per patient, never changed. */
public interface ConsultationReportRepository extends JpaRepository<ConsultationReport, UUID> {

    /** A patient's reports, newest first (index on patient_id, created_at). */
    List<ConsultationReport> findByPatientIdOrderByCreatedAtDesc(UUID patientId);
}
