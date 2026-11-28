package com.example.apimedlink.service;

import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Reconcile the search projection with out-of-band Keycloak changes; verify live identity on actual booking. */
@Service
public class PractitionerIdentityReconciler {
    private static final Logger log = LoggerFactory.getLogger(PractitionerIdentityReconciler.class);
    private final JdbcTemplate db;
    private final KeycloakAdminService identity;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public PractitionerIdentityReconciler(JdbcTemplate db, KeycloakAdminService identity, AuditService audit,
            TransactionTemplate tx, @Value("${app.practitioner-identity.enabled:false}") boolean enabled) {
        this.db = db;
        this.identity = identity;
        this.audit = audit;
        this.tx = tx;
        this.enabled = enabled;
    }

    /** Production and staging fail closed if the identity provider is unavailable or the practitioner is inactive. */
    public boolean eligibleNow(UUID id) {
        return !enabled || identity.isActivePractitioner(id);
    }

    @Scheduled(initialDelayString = "5s", fixedDelayString = "5m")
    public void reconcile() {
        if (!enabled) return;
        for (UUID id : db.query("select id from practitioners order by id", (row, n) -> row.getObject(1, UUID.class))) {
            try {
                reconcileOne(id);
            } catch (RuntimeException failure) {
                // Do not turn a transient identity outage into an accidental mass disable.
                log.warn("Practitioner identity reconciliation failed for {} ({})", id, failure.getClass().getSimpleName());
            }
        }
    }

    public void reconcileOne(UUID id) {
        boolean eligible = identity.isActivePractitioner(id);
        tx.executeWithoutResult(status -> {
            int changed = db.update("update practitioners set booking_enabled=? where id=? "
                    + "and booking_enabled is distinct from ? and not exists (select 1 from account_operations "
                    + "where user_id=? and status='PENDING')", eligible, id, eligible, id);
            if (changed == 1) {
                audit.recordWithin(AuditAction.ACCOUNT_UPDATED, "PRACTITIONER_BOOKING_ELIGIBILITY",
                        id.toString(), AuditOutcome.SUCCESS);
            }
        });
    }
}
