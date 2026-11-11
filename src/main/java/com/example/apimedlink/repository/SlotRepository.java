package com.example.apimedlink.repository;

import com.example.apimedlink.model.Slot;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Practitioners' bookable slots (MLink-97); searched with optional filters (MLink-98). */
public interface SlotRepository extends JpaRepository<Slot, UUID>, JpaSpecificationExecutor<Slot> {

    /** A page of slots with their practitioner, specialty and clinic loaded in the same query (no N+1). */
    @Override
    @EntityGraph(attributePaths = {"practitioner", "practitioner.specialty", "clinic"})
    Page<Slot> findAll(Specification<Slot> spec, Pageable pageable);

    /**
     * The slot a patient books, its row locked until the booking transaction ends ({@code SELECT … FOR UPDATE}): two
     * patients booking it at once are served one after the other, and the second sees it taken (MLink-102/103).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Slot s where s.id = :id")
    Optional<Slot> findForBookingById(@Param("id") UUID id);
}
