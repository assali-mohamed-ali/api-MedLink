package com.example.apimedlink.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** US-16: a patient asked for their data to be deleted; staff decide. The request alone deletes nothing. One per patient. */
@Entity
@Table(name = "deletion_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeletionRequest {

    @Id
    private UUID patientId;

    @Column(nullable = false, updatable = false)
    private Instant requestedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DeletionStatus status = DeletionStatus.PENDING;

    private UUID decidedBy;

    private Instant decidedAt;

    @Column(length = 500)
    private String decisionNote;

    public DeletionRequest(UUID patientId, Instant requestedAt) {
        this.patientId = patientId;
        this.requestedAt = requestedAt;
    }

    /** Staff's decision on a pending request. */
    public void decide(DeletionStatus decision, UUID staffId, Instant at, String note) {
        this.status = decision;
        this.decidedBy = staffId;
        this.decidedAt = at;
        this.decisionNote = note;
    }
}
