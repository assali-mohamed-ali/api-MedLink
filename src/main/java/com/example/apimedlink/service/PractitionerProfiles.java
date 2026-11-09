package com.example.apimedlink.service;

import java.util.Collection;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The practitioner profile of an account that already has its users row (MLink-69, review of PR #112). A
 * practitioner is a user with one more row (practitioners.id = users.id), so an existing account becomes one by
 * adding that row, and stops being one by deleting it. Joins the caller's transaction.
 */
@Component
public class PractitionerProfiles {

    private final JdbcClient db;

    public PractitionerProfiles(JdbcClient db) {
        this.db = db;
    }

    public void create(UUID id, String firstName, String lastName, UUID specialtyId, Collection<UUID> clinicIds) {
        db.sql("""
                        INSERT INTO practitioners (id, first_name, last_name, specialty_id)
                        VALUES (:id, :firstName, :lastName, :specialtyId)
                        """)
                .param("id", id)
                .param("firstName", firstName)
                .param("lastName", lastName)
                .param("specialtyId", specialtyId)
                .update();
        for (UUID clinicId : clinicIds) {
            db.sql("INSERT INTO practitioner_clinics (practitioner_id, clinic_id) VALUES (:id, :clinicId)")
                    .param("id", id)
                    .param("clinicId", clinicId)
                    .update();
        }
    }

    /** Any slot, past or future: slots and the appointments on them keep pointing at the practitioner. */
    public boolean hasSlots(UUID id) {
        return db.sql("SELECT EXISTS (SELECT 1 FROM slots WHERE practitioner_id = :id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    /** Removes the profile and its clinics (cascade); the users row stays. */
    public void delete(UUID id) {
        db.sql("DELETE FROM practitioners WHERE id = :id").param("id", id).update();
    }
}
