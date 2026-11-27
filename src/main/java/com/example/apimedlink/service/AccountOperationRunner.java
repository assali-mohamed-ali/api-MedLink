package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.exception.KeycloakUnavailableException;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.repository.AccountOperation;
import com.example.apimedlink.repository.AccountOperationStore;
import com.example.apimedlink.security.CurrentUser;
import jakarta.persistence.EntityNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * MLink-72 (review of #122): durable, idempotent auditing of the account operations that reach Keycloak, which no
 * transaction covers.
 *
 * <p>An operation is registered first: its record and its ATTEMPTED audit entry are written in ONE transaction, with
 * the same operation id; when that fails nothing is done (503). It is then carried out, outside any transaction,
 * and closed in ONE transaction: the record becomes DONE and the SUCCESS entry is written, with the same operation
 * id. If the process dies at any point after the registration, the record is still PENDING: the worker takes it
 * again (every step of an operation is idempotent) and closes it. Nothing stays "attempted" forever, and a retry
 * is never a second, uncorrelated attempt: asking again for the same change reuses the pending operation.
 *
 * <p>A refusal (a business rule, an unknown user) closes the operation at once as FAILED with a FAILURE entry. An
 * outage (Keycloak, the database) leaves it PENDING, to be retried with a growing delay, until the maximum number
 * of tries is reached: then it is closed as FAILED with a FAILURE entry. The closing entry is written once: the
 * close only succeeds while the record is PENDING, and the database allows one closing entry per operation id.
 *
 * <p>An operation is claimed just before it runs, with a lease and a token of its own: the lease of one that waits
 * its turn never runs out. Only the holder of the current token can close it or put it back to wait: a worker
 * whose lease ended while it was busy, and whose operation another worker took over, changes nothing any more
 * (the steps of an operation are idempotent, so both having run them is harmless).
 */
@Service
public class AccountOperationRunner {

    private static final Logger log = LoggerFactory.getLogger(AccountOperationRunner.class);

    private static final int BATCH = 20;
    private static final long FIRST_DELAY_SECONDS = 30;
    private static final long MAX_DELAY_SECONDS = 900;

    private final AccountOperationStore store;
    private final TransactionTemplate tx;
    private final CurrentUser currentUser;
    private final int maxAttempts;
    private final long leaseSeconds;

    public AccountOperationRunner(AccountOperationStore store, TransactionTemplate tx, CurrentUser currentUser,
            @Value("${app.account-operations.max-attempts:8}") int maxAttempts,
            @Value("${app.account-operations.lease-seconds:120}") long leaseSeconds) {
        this.store = store;
        this.tx = tx;
        this.currentUser = currentUser;
        this.maxAttempts = maxAttempts;
        this.leaseSeconds = leaseSeconds;
    }

    /**
     * Registers the operation (or finds the same pending one), then carries it out now.
     *
     * @throws ApiException 503 {@code AUDIT_UNAVAILABLE} when the registration or the closing cannot be written,
     *     409 {@code OPERATION_PENDING} when another change of this account is pending or running; the error of
     *     the operation itself otherwise (the operation then stays PENDING for a retry, or is closed as FAILED)
     */
    public void submit(AccountOperation request, Consumer<AccountOperation> executor) {
        Instant now = Instant.now();
        AccountOperation mine = request.requestedBy(currentUser.idOrNull(), clientIp());
        UUID operationId;
        try {
            operationId = tx.execute(status -> register(mine, now));
        } catch (ApiException refused) {
            throw refused;
        } catch (RuntimeException e) {
            log.error("AUDIT_UNAVAILABLE: {} for {} was not started, its entry cannot be written ({})",
                    mine.action(), mine.userId(), e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUDIT_UNAVAILABLE",
                    "L'opération n'a pas été exécutée : sa trace n'a pas pu être enregistrée. Réessayez.");
        }
        AccountOperation leased = store.lease(operationId, now, leaseSeconds).orElseThrow(() -> new ApiException(
                HttpStatus.CONFLICT, "OPERATION_PENDING",
                "Cette opération est déjà en cours : elle sera terminée automatiquement."));
        execute(leased, executor, now);
    }

    /**
     * US-16: registers the operation inside the caller's transaction (record and ATTEMPTED entry), so it commits or rolls
     * back with the change that needs it (an approved deletion request closing the account). Run it after the commit
     * with {@link #runRegistered}; if that never happens, the worker takes it like any other pending operation.
     *
     * @throws ApiException 409 {@code OPERATION_PENDING} when another change of this account is pending
     */
    public UUID registerWithin(AccountOperation request) {
        AccountOperation mine = request.requestedBy(currentUser.idOrNull(), clientIp());
        return tx.execute(status -> register(mine, Instant.now()));
    }

    /**
     * Carries out an operation registered by {@link #registerWithin}, once its transaction has committed. Does nothing
     * when the worker holds it already. Its error goes on to the caller; the operation then stays pending or is closed
     * as FAILED, as for {@link #submit}.
     */
    public void runRegistered(UUID operationId, Consumer<AccountOperation> executor) {
        Instant now = Instant.now();
        Optional<AccountOperation> leased = store.lease(operationId, now, leaseSeconds);
        if (leased.isPresent()) {
            execute(leased.get(), executor, now);
        }
    }

    /** The record and the ATTEMPTED entry, together; the operation already pending for this account if it is the same. */
    private UUID register(AccountOperation mine, Instant now) {
        Optional<AccountOperation> pending = store.findPendingFor(mine.userId());
        if (pending.isPresent()) {
            if (pending.get().sameRequestAs(mine)) {
                return pending.get().id(); // the same change again: no second attempt, the same operation
            }
            throw new ApiException(HttpStatus.CONFLICT, "OPERATION_PENDING",
                    "Une autre opération est déjà en cours pour ce compte : elle sera terminée automatiquement.");
        }
        store.insert(mine, now);
        store.appendAudit(mine.id(), mine.action(), mine.userId(), mine.requestedBy(), mine.ipAddress(),
                AuditOutcome.ATTEMPTED);
        return mine.id();
    }

    /** Carries out a leased operation and closes it; the error of a failed operation goes on to the caller. */
    public void execute(AccountOperation op, Consumer<AccountOperation> executor, Instant now) {
        try {
            executor.accept(op);
        } catch (RuntimeException failed) {
            settle(op, failed, now);
            throw failed;
        }
        complete(op, now);
    }

    /** The operations that are due at {@code now}, carried out one by one (the tests choose the time). */
    public int runDue(Instant now, Consumer<AccountOperation> executor) {
        return runDue(() -> now, executor);
    }

    /**
     * The operations that are due, carried out one by one, at most one batch per call; one that fails never stops the
     * others. Each one is claimed just before it runs, at the time the clock gives then.
     */
    public int runDue(Supplier<Instant> clock, Consumer<AccountOperation> executor) {
        int taken = 0;
        while (taken < BATCH) {
            Instant now = clock.get();
            Optional<AccountOperation> next = store.claimNext(now, leaseSeconds);
            if (next.isEmpty()) {
                break;
            }
            taken++;
            AccountOperation op = next.get();
            try {
                execute(op, executor, now);
            } catch (RuntimeException e) {
                log.warn("Account operation {} ({}) did not complete: {}", op.id(), op.action(),
                        e.getClass().getSimpleName());
            }
        }
        return taken;
    }

    /** After a failure: closed as FAILED for a refusal or when the tries are used up, otherwise put back to wait. */
    private void settle(AccountOperation op, RuntimeException failed, Instant now) {
        String error = failed.getClass().getSimpleName();
        try {
            if (isRefusal(failed) || op.attempts() >= maxAttempts) {
                close(op, "FAILED", AuditOutcome.FAILURE, now, error);
            } else {
                store.reschedule(op.id(), op.leaseToken(), now.plus(backoff(op.attempts())), error);
            }
        } catch (RuntimeException bookkeeping) {
            // The original error stays the one the caller gets; the operation is still PENDING, so it is tried again
            failed.addSuppressed(bookkeeping);
            log.error("AUDIT_GAP: operation {} ({}) could not be updated after its failure ({}): it stays pending",
                    op.id(), op.action(), bookkeeping.getClass().getSimpleName());
        }
    }

    private void complete(AccountOperation op, Instant now) {
        try {
            close(op, "DONE", AuditOutcome.SUCCESS, now, null);
        } catch (RuntimeException e) {
            log.error("AUDIT_UNAVAILABLE: {} for {} was done but its closing entry cannot be written ({}): "
                    + "it stays pending and is closed by the next try", op.action(), op.userId(),
                    e.getClass().getSimpleName());
            try {
                store.reschedule(op.id(), op.leaseToken(), now, "AuditUnavailable"); // frees the lease: the next call or poll closes it
            } catch (RuntimeException ignored) {
                // the lease ends by itself
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUDIT_UNAVAILABLE",
                    "L'opération a été exécutée mais sa trace n'a pas pu être finalisée : elle sera finalisée "
                            + "automatiquement.");
        }
    }

    /** The record and the closing entry, together, and only once, and only for the holder of the current lease. */
    private void close(AccountOperation op, String status, AuditOutcome outcome, Instant now, String error) {
        tx.executeWithoutResult(s -> {
            if (store.close(op.id(), op.leaseToken(), status, now, error)) {
                store.appendAudit(op.id(), op.action(), op.userId(), op.requestedBy(), op.ipAddress(), outcome);
            }
        });
    }

    /** A refusal does not change by waiting; a Keycloak outage or a database error might. */
    private static boolean isRefusal(RuntimeException failed) {
        return (failed instanceof ApiException && !(failed instanceof KeycloakUnavailableException))
                || failed instanceof EntityNotFoundException;
    }

    private static Duration backoff(int attempts) {
        long seconds = FIRST_DELAY_SECONDS << Math.min(Math.max(attempts - 1, 0), 5);
        return Duration.ofSeconds(Math.min(MAX_DELAY_SECONDS, seconds));
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request) {
            return request.getRequest().getRemoteAddr();
        }
        return null;
    }
}
