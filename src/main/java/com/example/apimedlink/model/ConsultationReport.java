package com.example.apimedlink.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

/**
 * A consultation report (MLink-32). The content is stored sealed ({@code contentEncrypted}, see ReportCipher), never
 * in plain text. Written once, never changed (the database refuses UPDATE and DELETE). The id is chosen by the
 * service before the insert, because it is part of what the content is sealed with.
 */
@Entity
@Table(name = "consultation_reports")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsultationReport implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID patientId;

    @Column(nullable = false, updatable = false)
    private UUID practitionerId;

    @Column(updatable = false)
    private UUID appointmentId;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String contentEncrypted;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** A report built here is new: saved with an INSERT, never a merge (its id is set before the insert). */
    @Transient
    @Getter(AccessLevel.NONE)
    private boolean fresh = true;

    public ConsultationReport(UUID id, UUID patientId, UUID practitionerId, String contentEncrypted) {
        this.id = id;
        this.patientId = patientId;
        this.practitionerId = practitionerId;
        this.contentEncrypted = contentEncrypted;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            // Microseconds, as PostgreSQL stores them: the time answered at creation is the time read later (Linux
            // clocks give nanoseconds; seen on #123's CI)
            createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
    }

    @PostPersist
    @PostLoad
    void markSaved() {
        fresh = false;
    }
}
