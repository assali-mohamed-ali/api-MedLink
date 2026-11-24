package com.example.apimedlink.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * MLink-123/125: the metadata of one document. The bytes are encrypted in the object store
 * ({@link DocumentStorage}); this row only says who uploaded what, for which patient, and how to
 * verify and decrypt it. The id is assigned before the insert, so {@link Persistable} tells Spring
 * Data it is new: a save is an insert, never a merge.
 */
@Entity
@Table(name = "patient_documents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PatientDocument implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID patientId;

    /** The staff member who uploaded it; null once that account has been deleted. */
    @Column(updatable = false)
    private UUID uploadedBy;

    @Column(nullable = false, updatable = false, length = 200)
    private String title;

    @Column(nullable = false, updatable = false, length = 255)
    private String originalFilename;

    @Column(nullable = false, updatable = false, length = 100)
    private String contentType;

    @Column(nullable = false, updatable = false)
    private long sizeBytes;

    @Column(nullable = false, updatable = false, length = 64)
    private String sha256;

    @Column(nullable = false, updatable = false, length = 255)
    private String storageKey;

    @Column(nullable = false, updatable = false)
    private int keyVersion;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    @Getter(AccessLevel.NONE)
    private boolean persisted;

    public PatientDocument(
            UUID id,
            UUID patientId,
            UUID uploadedBy,
            String title,
            String originalFilename,
            String contentType,
            long sizeBytes,
            String sha256,
            String storageKey,
            int keyVersion,
            Instant createdAt) {
        this.id = id;
        this.patientId = patientId;
        this.uploadedBy = uploadedBy;
        this.title = title;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.storageKey = storageKey;
        this.keyVersion = keyVersion;
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        persisted = true;
    }
}
