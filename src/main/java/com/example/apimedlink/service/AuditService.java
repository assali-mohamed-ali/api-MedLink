package com.example.apimedlink.service;

import com.example.apimedlink.exception.AuditUnavailableException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditEntry;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.repository.AuditEntryRepository;
import com.example.apimedlink.security.CurrentUser;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The one audit trail every feature writes to (MLink-59). It finds the caller and the IP itself. {@link #record}
 * saves in its own transaction, so the entry is kept even when the request's work rolls back (a refusal, a failure);
 * {@link #recordWithin} saves in the caller's transaction, so a change and its entry are kept or lost together.
 */
@Service
public class AuditService {

    private final AuditEntryRepository entries;
    private final CurrentUser currentUser;

    public AuditService(AuditEntryRepository entries, CurrentUser currentUser) {
        this.entries = entries;
        this.currentUser = currentUser;
    }

    /** Its own transaction: kept even when the request's work rolls back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, String resourceType, String resourceId, AuditOutcome outcome) {
        record(action, resourceType, resourceId, outcome, currentUser.idOrNull());
    }

    /**
     * Same, with the user given by the caller: for the moments no session exists yet (a login provisions the
     * account before it opens one), so the known user id is not lost.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            AuditAction action, String resourceType, String resourceId, AuditOutcome outcome, UUID userId) {
        entries.save(new AuditEntry(userId, action, resourceType, resourceId, clientIp(), outcome));
    }

    /**
     * MLink-123/125: the entry must commit with the work it describes. The caller owns the transaction
     * (MANDATORY); a failure becomes a 503 so the work is not done without a trace.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordInCurrentTransaction(
            AuditAction action, String resourceType, String resourceId, AuditOutcome outcome) {
        recordInCurrentTransaction(action, resourceType, resourceId, outcome, currentUser.idOrNull());
    }

    /**
     * Same, with the user given by the caller: for the moments no session exists yet (a login provisions the
     * account before it opens one), so the known user id is not lost.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordInCurrentTransaction(
            AuditAction action, String resourceType, String resourceId, AuditOutcome outcome, UUID userId) {
        try {
            entries.saveAndFlush(new AuditEntry(userId, action, resourceType, resourceId, clientIp(), outcome));
        } catch (DataAccessException failure) {
            throw new AuditUnavailableException("Le journal d'audit est indisponible.", failure);
        }
    }

    /**
     * The entry of a successful change, in the transaction that makes the change (MLink-26, review on #116): if the
     * entry cannot be written, the change rolls back with it, so no change is ever kept without its trace and the
     * caller never gets an error for a change that was kept. Must be called inside that transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordWithin(AuditAction action, String resourceType, String resourceId, AuditOutcome outcome) {
        entries.save(new AuditEntry(currentUser.idOrNull(), action, resourceType, resourceId, clientIp(), outcome));
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request) {
            return request.getRequest().getRemoteAddr();
        }
        return null;
    }
}
