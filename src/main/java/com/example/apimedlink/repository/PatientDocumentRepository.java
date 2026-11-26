package com.example.apimedlink.repository;

import com.example.apimedlink.document.PatientDocument;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PatientDocumentRepository extends JpaRepository<PatientDocument, UUID> {

    Optional<PatientDocument> findByIdAndPatientId(UUID id, UUID patientId);

    Page<PatientDocument> findByPatientId(UUID patientId, Pageable pageable);

    boolean existsByPatientId(UUID patientId);
}
