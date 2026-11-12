package com.example.apimedlink.service;

import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditEntry;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.repository.AuditEntryRepository;
import com.example.apimedlink.security.CurrentUser;
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, String resourceType, String resourceId, AuditOutcome outcome) {
        entries.save(new AuditEntry(currentUser.idOrNull(), action, resourceType, resourceId, clientIp(), outcome));
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
