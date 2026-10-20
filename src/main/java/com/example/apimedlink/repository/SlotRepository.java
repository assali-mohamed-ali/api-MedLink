package com.example.apimedlink.repository;

import com.example.apimedlink.model.Slot;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Practitioners' bookable slots (MLink-97). */
public interface SlotRepository extends JpaRepository<Slot, UUID> {}
