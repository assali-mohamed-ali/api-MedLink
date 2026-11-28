package com.example.apimedlink.repository;

import com.example.apimedlink.model.AuditEntry;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Append-only (MLink-59): save and read, nothing else. A plain Repository has no delete method to misuse. */
public interface AuditEntryRepository extends Repository<AuditEntry, UUID>, JpaSpecificationExecutor<AuditEntry> {

    AuditEntry save(AuditEntry entry);

    AuditEntry saveAndFlush(AuditEntry entry);

    interface AnomalyGroup {
        AuditAction getAction();
        UUID getUserId();
        String getIpAddress();
        Instant getLastSeen();
        long getTotal();
    }

    @Query("""
            select e.action as action, e.userId as userId, e.ipAddress as ipAddress,
                   max(e.timestamp) as lastSeen, count(e) as total
            from AuditEntry e
            where e.timestamp > :from and e.timestamp <= :to
              and ((e.action = :login and e.outcome = :failure) or e.action = :denied)
              and (e.userId is not null or e.ipAddress is not null)
            group by e.action, e.userId, e.ipAddress
            having count(e) >= :threshold""")
    List<AnomalyGroup> findAnomalyGroups(@Param("from") Instant from, @Param("to") Instant to,
            @Param("login") AuditAction login, @Param("failure") AuditOutcome failure,
            @Param("denied") AuditAction denied, @Param("threshold") int threshold);
}
