package com.example.apimedlink.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A patient's appointment on a slot. {@code patientId} is the owner (checked on cancel and reschedule);
 * {@code bookedBy} is who made the booking. Both are Keycloak ids (= users.id).
 */
@Entity
@Table(name = "appointments")
@Getter
@Setter
@NoArgsConstructor
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false)
    private Slot slot;

    @Column(nullable = false)
    private UUID patientId;

    @Column(nullable = false)
    private UUID bookedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AppointmentStatus status = AppointmentStatus.BOOKED;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant cancelledAt;

    private Instant reminderSentAt;

    public Appointment(Slot slot, UUID patientId, UUID bookedBy) {
        this.slot = slot;
        this.patientId = patientId;
        this.bookedBy = bookedBy;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
