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

/** US-29: a proposal to move a future appointment to another practitioner's free slot in the same clinic. */
@Entity
@Table(name = "appointment_reassignments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reassignment {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID appointmentId;

    @Column(nullable = false, updatable = false)
    private UUID targetSlotId;

    @Column(updatable = false)
    private UUID proposedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReassignmentStatus status = ReassignmentStatus.PENDING;

    private Instant practitionerAcceptedAt;

    private Instant patientAcceptedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant closedAt;

    public Reassignment(UUID appointmentId, UUID targetSlotId, UUID proposedBy, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.appointmentId = appointmentId;
        this.targetSlotId = targetSlotId;
        this.proposedBy = proposedBy;
        this.createdAt = createdAt;
    }

    public void practitionerAccepts(Instant at) {
        this.practitionerAcceptedAt = at;
    }

    public void patientAccepts(Instant at) {
        this.patientAcceptedAt = at;
    }

    public boolean acceptedByBoth() {
        return practitionerAcceptedAt != null && patientAcceptedAt != null;
    }

    public void close(ReassignmentStatus outcome, Instant at) {
        this.status = outcome;
        this.closedAt = at;
    }
}
