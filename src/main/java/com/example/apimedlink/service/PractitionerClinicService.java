package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.Clinic;
import com.example.apimedlink.model.Practitioner;
import com.example.apimedlink.repository.ClinicRepository;
import com.example.apimedlink.repository.PractitionerRepository;
import com.example.apimedlink.repository.SlotRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-29: staff set the clinics a practitioner works in. A clinic cannot be taken off a practitioner who still holds
 * a slot there, whatever its status (the database refuses it too, V13): this is the conservative rule, pending the
 * team's decision on removing a clinic with future appointments. Takes and returns entities and plain values.
 */
@Service
public class PractitionerClinicService {

    static final String RESOURCE = "PRACTITIONER_CLINICS";

    private final PractitionerRepository practitioners;
    private final ClinicRepository clinics;
    private final SlotRepository slots;
    private final AuditService audit;

    public PractitionerClinicService(PractitionerRepository practitioners, ClinicRepository clinics,
            SlotRepository slots, AuditService audit) {
        this.practitioners = practitioners;
        this.clinics = clinics;
        this.slots = slots;
        this.audit = audit;
    }

    /** Replaces the practitioner's clinics by {@code clinicIds}; returns the practitioner with the new list. */
    @Transactional
    public Practitioner assign(UUID practitionerId, List<UUID> clinicIds) {
        Practitioner practitioner = practitioners.findById(practitionerId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "PRACTITIONER_NOT_FOUND", "Ce praticien n'existe pas."));
        Set<UUID> wanted = new HashSet<>(clinicIds);
        List<Clinic> found = clinics.findAllById(wanted);
        if (found.size() != wanted.size()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CLINIC_NOT_FOUND", "Une des cliniques n'existe pas.");
        }
        for (Clinic current : Set.copyOf(practitioner.getClinics())) {
            if (!wanted.contains(current.getId()) && slots.holdsSlotsIn(practitionerId, current.getId())) {
                throw new ApiException(HttpStatus.CONFLICT, "CLINIC_HAS_SLOTS",
                        "Ce praticien a encore des créneaux dans la clinique " + current.getName()
                                + " : elle ne peut pas lui être retirée.");
            }
        }
        practitioner.getClinics().clear();
        practitioner.getClinics().addAll(found);
        Practitioner saved = practitioners.saveAndFlush(practitioner);
        audit.recordWithin(AuditAction.ACCOUNT_UPDATED, RESOURCE, practitionerId.toString(), AuditOutcome.SUCCESS);
        // open-in-view is off and the mapper runs after the transaction: load what it reads
        saved.getSpecialty().getName();
        return saved;
    }
}
