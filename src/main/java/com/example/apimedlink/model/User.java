package com.example.apimedlink.model;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A MedLink account. Each role is a subclass with its own table sharing this id
 * ({@code JOINED}): a patient is a {@link Patient}. A person holds one role
 * only, so there is
 * never a second profile for the same login.
 */
@Entity
@Table(name = "users")
@Inheritance(strategy = InheritanceType.JOINED)
@Getter
@Setter
@NoArgsConstructor
public class User implements Persistable<UUID> {

    /**
     * Keycloak user id ({@code sub} claim): given by Keycloak, never generated
     * here.
     * Passwords and roles live in Keycloak only.
     */
    @Id
    private UUID id;

    /** The login name in Keycloak: the email for patients. */
    @Column(nullable = false, unique = true, length = 254)
    private String username;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * The id is assigned, so Spring Data cannot tell a new row from an existing one
     * by itself.
     */
    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean persisted;

    @Builder
    public User(UUID id, String username, String email, Instant createdAt) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.createdAt = createdAt;
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

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @Transient
    private boolean updated;

    public void markUpdated() {
        this.updated = true;
    }

    public boolean wasUpdated() {
        return updated;
    }
}
