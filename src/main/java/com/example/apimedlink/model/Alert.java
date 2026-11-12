package com.example.apimedlink.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An alert for a practitioner (MLink-37): kept until it is read, so it waits for a practitioner who was offline.
 * Only its reading time ever changes, once.
 */
@Entity
@Table(name = "alerts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID practitionerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 40)
    private AlertType type;

    @Column(updatable = false)
    private UUID appointmentId;

    @Column(nullable = false, updatable = false, length = 500)
    private String message;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant readAt;

    public Alert(UUID practitionerId, AlertType type, UUID appointmentId, String message) {
        this.practitionerId = practitionerId;
        this.type = type;
        this.appointmentId = appointmentId;
        this.message = message;
    }

    /** Marks the alert read; reading it again keeps the first time. */
    public void markRead(Instant at) {
        if (readAt == null) {
            readAt = at;
        }
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
