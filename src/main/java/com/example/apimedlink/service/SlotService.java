package com.example.apimedlink.service;

import com.example.apimedlink.model.Slot;
import com.example.apimedlink.model.SlotStatus;
import com.example.apimedlink.repository.SlotRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    public SlotService(SlotRepository slots) {
        this.slots = slots;
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
