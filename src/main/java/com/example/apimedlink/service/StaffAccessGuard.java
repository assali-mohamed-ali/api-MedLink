package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** US-28: serialize staff removals across replicas without moving the durable account operation into one DB transaction. */
@Service
public class StaffAccessGuard {
    private final JdbcTemplate jdbc;
    private final KeycloakAdminService keycloak;
    private final TransactionTemplate lock;
    private final TransactionTemplate outside;

    public StaffAccessGuard(JdbcTemplate jdbc, KeycloakAdminService keycloak, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.keycloak = keycloak;
        this.lock = new TransactionTemplate(manager);
        lock.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.outside = new TransactionTemplate(manager);
        outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    public void protect(UUID target, Runnable operation) {
        lock.executeWithoutResult(status -> {
            // A transaction-scoped advisory lock is released on commit, rollback or connection loss.
            // Fail immediately rather than exhausting the connection pool while another removal is running.
            Boolean acquired = jdbc.queryForObject("select pg_try_advisory_xact_lock(?, ?)",
                    Boolean.class, 28029, 1);
            if (!Boolean.TRUE.equals(acquired)) {
                throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_CHANGE_BUSY",
                        "Une modification de compte est en cours. Réessayez.");
            }
            // The original steps commit independently, as required by session revocation and recovery.
            outside.executeWithoutResult(ignored -> {
                if (keycloak.isLastActiveStaff(target)) {
                    throw new ApiException(HttpStatus.CONFLICT, "LAST_ACTIVE_STAFF",
                            "Le dernier compte administratif actif ne peut pas être désactivé ou rétrogradé.");
                }
                operation.run();
            });
        });
    }
}
