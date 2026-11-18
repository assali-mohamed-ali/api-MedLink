package com.example.apimedlink.repository;

import com.example.apimedlink.model.Appointment;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Read-only view of the appointments for statistics (MLink-95): it never reads a patient or a name. */
public interface AppointmentStatsRepository extends Repository<Appointment, UUID> {

    /**
     * The appointments whose slot starts in {@code [from, to)}, reduced to what a statistic may know. The read ends
     * when the method returns, before the audit entry of the export is written in its own transaction.
     */
    @Transactional(readOnly = true)
    @Query("""
            select new com.example.apimedlink.repository.AppointmentFact(s.startsAt, a.status, sp.name)
            from Appointment a
              join a.slot s
              join s.practitioner pr
              join pr.specialty sp
            where s.startsAt >= :from and s.startsAt < :to""")
    List<AppointmentFact> findFacts(@Param("from") Instant from, @Param("to") Instant to);
}
