package com.example.apimedlink.service;

import com.example.apimedlink.exception.AuditUnavailableException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditEntry;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.repository.AuditEntryRepository;
import com.example.apimedlink.security.CurrentUser;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The one audit trail every feature writes to (MLink-59). It finds the caller and the IP itself, and
 * saves in its own transaction so the entry is kept even when the request's work rolls back.
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
        recordInCurrentTransaction(action, resourceType, resourceId, outcome);
    }

    /**
     * MLink-123/125: the entry must commit with the work it describes. The caller owns the transaction
     * (MANDATORY); a failure becomes a 503 so the work is not done without a trace.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordInCurrentTransaction(
            AuditAction action, String resourceType, String resourceId, AuditOutcome outcome) {
        try {
            entries.saveAndFlush(new AuditEntry(currentUser.idOrNull(), action, resourceType, resourceId,
                    clientIp(), outcome));
        } catch (DataAccessException failure) {
            throw new AuditUnavailableException("Le journal d'audit est indisponible.", failure);
        }
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request) {
            return request.getRequest().getRemoteAddr();
        }
        return null;
    }
}
