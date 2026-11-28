package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.Clinic;
import com.example.apimedlink.model.Practitioner;
import com.example.apimedlink.repository.ClinicRepository;
import com.example.apimedlink.repository.PractitionerRepository;
import com.example.apimedlink.repository.SlotRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-29: staff set active clinic assignments. Future open slots and unresolved bookings block removal;
 * historical slot foreign keys survive in inactive assignments. Takes and returns entities and plain values.
 */
@Service
public class PractitionerClinicService {

    static final String RESOURCE = "PRACTITIONER_CLINICS";

    private final PractitionerRepository practitioners;
    private final ClinicRepository clinics;
    private final SlotRepository slots;
    private final AuditService audit;
    private final EntityManager entities;

    public PractitionerClinicService(PractitionerRepository practitioners, ClinicRepository clinics,
            SlotRepository slots, AuditService audit, EntityManager entities) {
        this.practitioners = practitioners;
        this.clinics = clinics;
        this.slots = slots;
        this.audit = audit;
        this.entities = entities;
    }

    /** Replaces the practitioner's clinics by {@code clinicIds}; returns the practitioner with the new list. */
    @Transactional
    public Practitioner assign(UUID practitionerId, List<UUID> clinicIds) {
        Practitioner practitioner = practitioners.lockSchedule(practitionerId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "PRACTITIONER_NOT_FOUND", "Ce praticien n'existe pas."));
        Set<UUID> wanted = new HashSet<>(clinicIds);
        List<Clinic> found = clinics.findAllById(wanted);
        if (found.size() != wanted.size()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CLINIC_NOT_FOUND", "Une des cliniques n'existe pas.");
        }
        Instant now = Instant.now();
        Set<Clinic> removed = new HashSet<>(practitioner.getClinics());
        removed.removeIf(clinic -> wanted.contains(clinic.getId()));
        for (Clinic current : removed) {
            if (slots.hasFutureOpenSlots(practitionerId, current.getId(), now)) {
                throw new ApiException(HttpStatus.CONFLICT, "CLINIC_HAS_SLOTS",
                        "Ce praticien a encore des créneaux ouverts ou des rendez-vous à réaffecter dans la clinique "
                                + current.getName() + ".");
            }
        }
        // Do not delete the FK target of an historical slot. Keep it inactive and hide it from new schedules.
        for (Clinic current : removed) {
            if (slots.holdsSlotsIn(practitionerId, current.getId())) {
                practitioners.deactivateClinic(practitionerId, current.getId());
            } else {
                practitioners.removeUnusedClinic(practitionerId, current.getId());
            }
        }
        for (UUID clinicId : wanted) {
            practitioners.activateClinic(practitionerId, clinicId);
        }
        entities.clear();
        Practitioner saved = practitioners.findById(practitionerId).orElseThrow();
        saved.getClinics().size();
        audit.recordWithin(AuditAction.ACCOUNT_UPDATED, RESOURCE, practitionerId.toString(), AuditOutcome.SUCCESS);
        // open-in-view is off and the mapper runs after the transaction: load what it reads
        saved.getSpecialty().getName();
        return saved;
    }
}
