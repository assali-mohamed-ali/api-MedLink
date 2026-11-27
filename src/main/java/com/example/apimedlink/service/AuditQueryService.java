package com.example.apimedlink.service;

import com.example.apimedlink.repository.PatientDocumentRepository;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditEntry;
import com.example.apimedlink.model.AuditResourceType;
import com.example.apimedlink.repository.AuditEntryRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only queries for the append-only audit trail. */
@Service
public class AuditQueryService {

    private final AuditEntryRepository entries;
    private final PatientDocumentRepository documents;

    public AuditQueryService(AuditEntryRepository entries, PatientDocumentRepository documents) {
        this.entries = entries;
        this.documents = documents;
    }

    @Transactional(readOnly = true)
    public Page<AuditEntry> search(
            Instant from, Instant to, UUID userId, UUID patientId, AuditAction action, int page, int size) {
        Pageable pageable = PageRequest.of(
                page, size, Sort.by(Sort.Order.desc("timestamp"), Sort.Order.desc("id")));
        List<String> documentIds = patientId == null ? List.of() :
                documents.findIdsByPatientId(patientId).stream().map(UUID::toString).toList();
        return entries.findAll((root, query, criteria) -> {
            List<Predicate> filters = new ArrayList<>();
            if (from != null) {
                filters.add(criteria.greaterThanOrEqualTo(root.get("timestamp"), from));
            }
            if (to != null) {
                filters.add(criteria.lessThanOrEqualTo(root.get("timestamp"), to));
            }
            if (userId != null) {
                filters.add(criteria.equal(root.get("userId"), userId));
            }
            if (patientId != null) {
                Predicate patientResource = criteria.and(
                        root.get("resourceType").in(AuditResourceType.PATIENT.name(),
                                PatientRecordService.RESOURCE, ConsultationReportService.RESOURCE_PATIENT_REPORTS),
                        criteria.equal(root.get("resourceId"), patientId.toString()));
                if (!documentIds.isEmpty()) {
                    patientResource = criteria.or(patientResource, criteria.and(
                            criteria.equal(root.get("resourceType"), AuditResourceType.PATIENT_DOCUMENT.name()),
                            root.get("resourceId").in(documentIds)));
                }
                filters.add(patientResource);
            }
            if (action != null) {
                filters.add(criteria.equal(root.get("action"), action));
            }
            return criteria.and(filters.toArray(Predicate[]::new));
        }, pageable);
    }
}
