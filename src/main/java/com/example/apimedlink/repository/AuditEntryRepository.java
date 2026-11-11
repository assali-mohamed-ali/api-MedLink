package com.example.apimedlink.repository;

import com.example.apimedlink.model.AuditEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Append-only (MLink-59): save and read, nothing else. A plain Repository has no delete method to misuse. */
public interface AuditEntryRepository extends Repository<AuditEntry, UUID> {

    AuditEntry save(AuditEntry entry);

    List<AuditEntry> findByUserIdOrderByTimestampDesc(UUID userId);
}
