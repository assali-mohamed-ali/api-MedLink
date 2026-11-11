package com.example.apimedlink.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditEntry;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.repository.AuditEntryRepository;
import com.example.apimedlink.security.CurrentUser;

/**
 * The one audit trail every feature writes to (MLink-59). It finds the caller
 * and the IP itself, and
 * saves in its own transaction so the entry is kept even when the request's
 * work rolls back.
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
    public void record(
            AuditAction action,
            String resourceType,
            String resourceId,
            AuditOutcome outcome) {
        record(action, resourceType, resourceId, outcome, currentUser.idOrNull());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            AuditAction action,
            String resourceType,
            String resourceId,
            AuditOutcome outcome,
            UUID userId) {
        entries.save(new AuditEntry(userId, action, resourceType, resourceId, clientIp(), outcome));
    }

    /**
     * Writes an audit entry in the caller's transaction, for the signed-in caller.
     * The entry is rolled back if the business transaction fails.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordInCurrentTransaction(
            AuditAction action,
            String resourceType,
            String resourceId,
            AuditOutcome outcome) {
        recordInCurrentTransaction(action, resourceType, resourceId, outcome, currentUser.idOrNull());
    }

    /**
     * Same, with the user given by the caller: for the moments no session exists yet (a login
     * provisions the account before it opens one), so the known user id is not lost.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordInCurrentTransaction(
            AuditAction action,
            String resourceType,
            String resourceId,
            AuditOutcome outcome,
            UUID userId) {
        entries.save(new AuditEntry(userId, action, resourceType, resourceId, clientIp(), outcome));
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request) {
            return request.getRequest().getRemoteAddr();
        }
        return null;
    }
}
