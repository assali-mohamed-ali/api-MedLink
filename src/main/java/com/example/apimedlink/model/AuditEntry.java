package com.example.apimedlink.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One line of the audit trail (MLink-59, AuditEntry in the class diagram). Written once, never
 * changed: no setters here, and the database rejects UPDATE and DELETE (V7). Never holds a password,
 * a token or a medical value.
 */
@Entity
@Table(name = "audit_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private Instant timestamp;

    /** The caller's Keycloak id (= users.id), null when anonymous. No foreign key: the row outlives the user. */
    @Column(updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 50)
    private AuditAction action;

    @Column(nullable = false, updatable = false, length = 50)
    private String resourceType;

    @Column(nullable = false, updatable = false)
    private String resourceId;

    @Column(updatable = false, length = 45)
    private String ipAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private AuditOutcome outcome;

    public AuditEntry(UUID userId, AuditAction action, String resourceType, String resourceId,
            String ipAddress, AuditOutcome outcome) {
        this.timestamp = Instant.now();
        this.userId = userId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.ipAddress = ipAddress;
        this.outcome = outcome;
    }
}
