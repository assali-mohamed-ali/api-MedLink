package com.example.apimedlink.repository;

import com.example.apimedlink.model.AuditEntry;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Append-only (MLink-59): save and read, nothing else. A plain Repository has no delete method to misuse. */
public interface AuditEntryRepository extends Repository<AuditEntry, UUID>, JpaSpecificationExecutor<AuditEntry> {

    AuditEntry save(AuditEntry entry);
}
