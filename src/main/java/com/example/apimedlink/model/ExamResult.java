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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

/** US-13 / US-25: an examination result, published once and never changed. Title and content are sealed. */
@Entity
@Table(name = "exam_results")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExamResult implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID patientId;

    @Column(nullable = false, updatable = false)
    private UUID practitionerId;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String titleEncrypted;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String typeEncrypted;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String contentEncrypted;

    /** The day the examination took place (not the publication). */
    @Column(nullable = false, updatable = false)
    private LocalDate examDate;

    /** One of the patient's documents, or null. */
    @Column(updatable = false)
    private UUID documentId;

    /** When the patient first saw it; set by a native update only (the entity is immutable). Null: new. */
    @Column(insertable = false, updatable = false)
    private Instant readAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    @Getter(AccessLevel.NONE)
    private boolean fresh = true;

    public ExamResult(UUID id, UUID patientId, UUID practitionerId, String titleEncrypted, String typeEncrypted,
            String contentEncrypted, LocalDate examDate, UUID documentId) {
        this.id = id;
        this.patientId = patientId;
        this.practitionerId = practitionerId;
        this.titleEncrypted = titleEncrypted;
        this.typeEncrypted = typeEncrypted;
        this.contentEncrypted = contentEncrypted;
        this.examDate = examDate;
        this.documentId = documentId;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
    }

    @PostPersist
    @PostLoad
    void markSaved() {
        fresh = false;
    }
}
