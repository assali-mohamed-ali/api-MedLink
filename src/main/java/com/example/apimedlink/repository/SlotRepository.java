package com.example.apimedlink.repository;

import com.example.apimedlink.model.Slot;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Practitioners' bookable slots (MLink-97); searched with optional filters (MLink-98). */
public interface SlotRepository extends JpaRepository<Slot, UUID>, JpaSpecificationExecutor<Slot> {

    /** A page of slots with their practitioner, specialty and clinic loaded in the same query (no N+1). */
    @Override
    @EntityGraph(attributePaths = {"practitioner", "practitioner.specialty", "clinic"})
    Page<Slot> findAll(Specification<Slot> spec, Pageable pageable);
}
