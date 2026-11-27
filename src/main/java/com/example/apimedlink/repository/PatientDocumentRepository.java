package com.example.apimedlink.repository;

import com.example.apimedlink.document.PatientDocument;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PatientDocumentRepository extends JpaRepository<PatientDocument, UUID> {

    Optional<PatientDocument> findByIdAndPatientId(UUID id, UUID patientId);

    Page<PatientDocument> findByPatientId(UUID patientId, Pageable pageable);

    @Query("select d.id from PatientDocument d where d.patientId = :patientId")
    List<UUID> findIdsByPatientId(@Param("patientId") UUID patientId);

    boolean existsByPatientId(UUID patientId);
}
