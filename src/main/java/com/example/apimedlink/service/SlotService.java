package com.example.apimedlink.service;

import com.example.apimedlink.model.Clinic;
import com.example.apimedlink.model.Practitioner;
import com.example.apimedlink.model.Slot;
import com.example.apimedlink.model.SlotStatus;
import com.example.apimedlink.model.Specialty;
import com.example.apimedlink.repository.ClinicRepository;
import com.example.apimedlink.repository.PractitionerRepository;
import com.example.apimedlink.repository.SlotRepository;
import com.example.apimedlink.repository.SpecialtyRepository;
import jakarta.persistence.criteria.Predicate;
import java.text.Collator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The slot search of a patient (MLink-98). Takes and returns entities and plain values, never a DTO. */
@Service
public class SlotService {

    private final SlotRepository slots;
    private final ClinicRepository clinics;
    private final SpecialtyRepository specialties;
    private final PractitionerRepository practitioners;

    public SlotService(SlotRepository slots, ClinicRepository clinics, SpecialtyRepository specialties,
            PractitionerRepository practitioners) {
        this.slots = slots;
        this.clinics = clinics;
        this.specialties = specialties;
        this.practitioners = practitioners;
    }

    /**
     * The lists of the search form (MLink-163): every clinic, specialty and practitioner (with their clinics), in
     * French order by name, so "Pédiatrie" sits before "Pneumologie". A few dozen rows: sorted here, not in SQL,
     * whose collation depends on the database.
     */
    @Transactional(readOnly = true)
    public SlotFilters filters() {
        Collator french = Collator.getInstance(Locale.FRENCH);
        return new SlotFilters(
                clinics.findAll().stream().sorted(Comparator.comparing(Clinic::getName, french)).toList(),
                specialties.findAll().stream().sorted(Comparator.comparing(Specialty::getName, french)).toList(),
                practitioners.findAllBy().stream()
                        .sorted(Comparator.comparing(Practitioner::getLastName, french)
                                .thenComparing(Practitioner::getFirstName, french))
                        .toList());
    }

    /**
     * Free slots starting in {@code [from, to)}, earliest first. A past {@code from} starts at now: a slot
     * that has already started is never offered. Each filter is optional and they combine.
     */
    @Transactional(readOnly = true)
    public Page<Slot> searchFree(Instant from, Instant to, UUID clinicId, UUID practitionerId, UUID specialtyId,
            int page, int size) {
        Instant now = Instant.now();
        Instant start = from.isBefore(now) ? now : from;
        Specification<Slot> spec = (root, query, cb) -> {
            List<Predicate> rules = new ArrayList<>();
            rules.add(cb.equal(root.get("status"), SlotStatus.FREE));
            rules.add(cb.greaterThanOrEqualTo(root.get("startsAt"), start));
            rules.add(cb.lessThan(root.get("startsAt"), to));
            if (clinicId != null) {
                rules.add(cb.equal(root.get("clinic").get("id"), clinicId));
            }
            if (practitionerId != null) {
                rules.add(cb.equal(root.get("practitioner").get("id"), practitionerId));
            }
            if (specialtyId != null) {
                rules.add(cb.equal(root.get("practitioner").get("specialty").get("id"), specialtyId));
            }
            return cb.and(rules.toArray(Predicate[]::new));
        };
        return slots.findAll(spec, PageRequest.of(page, size, Sort.by("startsAt", "id")));
    }
}
