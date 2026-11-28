package com.example.apimedlink.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** US-21: a period when a practitioner cannot see patients. Never changed once declared. */
@Entity
@Table(name = "practitioner_absences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Absence {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID practitionerId;

    @Column(nullable = false, updatable = false)
    private Instant startsAt;

    @Column(nullable = false, updatable = false)
    private Instant endsAt;

    @Column(updatable = false, length = 200)
    private String reason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public Absence(UUID practitionerId, Instant startsAt, Instant endsAt, String reason, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.practitionerId = practitionerId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.reason = reason;
        this.createdAt = createdAt;
    }
}
