package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.exception.KeycloakUnavailableException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.AccountOperation;
import com.example.apimedlink.repository.AccountOperationStore;
import com.example.apimedlink.repository.UserRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * MLink-72 (review of #122): the account operations that reach Keycloak are durable and correlated. The operation is
 * registered with its ATTEMPTED entry before anything is touched, closed once with its closing entry carrying the
 * same operation id, and carried out again by the worker after an outage or a crash. Real database; Keycloak is a
 * mock. The tests choose the time the worker runs at, so no test waits.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccountOperationTest {

    @Autowired
    AdminUserService admin;

    @Autowired
    AccountOperationWorker worker;

    @Autowired
    AccountOperationRunner runner;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    KeycloakAdminService keycloak;

    @MockitoBean
    JavaMailSender mailSender;

    @MockitoSpyBean
    AccountOperationStore store;

    UUID id;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID();
        String email = "op-" + id + "@example.com";
        users.save(User.builder().id(id).username(email).email(email).build());
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage((Session) null));
    }

    private List<String> outcomes(AuditAction action) {
        return jdbc.queryForList("select outcome from audit_entries where resource_id = ? and action = ? "
                + "order by timestamp", String.class, id.toString(), action.name());
    }

    private String status() {
        return jdbc.queryForObject("select status from account_operations where user_id = ? "
                + "order by created_at desc limit 1", String.class, id);
    }

    private long operations() {
        Long count = jdbc.queryForObject("select count(*) from account_operations where user_id = ?", Long.class, id);
        return count == null ? 0 : count;
    }

    private static Instant later(int hours) {
        return Instant.now().plus(Duration.ofHours(hours));
    }

    /** An operation registered with its ATTEMPTED entry and due, as the request that started it would leave it. */
    private AccountOperation pending(UUID user, Instant createdAt) {
        AccountOperation op = AccountOperation.statusChange(user, false).requestedBy(UUID.randomUUID(), "10.0.0.1");
        store.insert(op, createdAt);
        store.appendAudit(op.id(), op.action(), user, op.requestedBy(), op.ipAddress(), AuditOutcome.ATTEMPTED);
        return op;
    }

    private String statusOf(UUID operationId) {
        return jdbc.queryForObject("select status from account_operations where id = ?", String.class, operationId);
    }

    private boolean held(UUID operationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select locked_until is not null from account_operations where id = ?", Boolean.class, operationId));
    }

    private void disable() {
        admin.setEnabled(id, false, UUID.randomUUID());
    }

    @Test
    void anOperationIsTracedAndSealedUnderOneOperationId() {
        disable();

        assertThat(outcomes(AuditAction.ACCOUNT_DISABLED)).containsExactly("ATTEMPTED", "SUCCESS");
        assertThat(jdbc.queryForObject("select count(distinct operation_id) from audit_entries "
                + "where resource_id = ? and action = ?", Long.class, id.toString(), "ACCOUNT_DISABLED"))
                .as("both entries carry the same operation id").isEqualTo(1L);
        assertThat(status()).isEqualTo("DONE");
    }

    @Test
    void anOutageLeavesAPendingOperationThatTheWorkerClosesOnce() {
        doThrow(new KeycloakUnavailableException("down")).doNothing().when(keycloak).setEnabled(id, false);

        assertThatThrownBy(this::disable).isInstanceOf(KeycloakUnavailableException.class);

        assertThat(outcomes(AuditAction.ACCOUNT_DISABLED)).containsExactly("ATTEMPTED");
        assertThat(status()).isEqualTo("PENDING");

        worker.runDue(later(1));

        assertThat(outcomes(AuditAction.ACCOUNT_DISABLED)).containsExactly("ATTEMPTED", "SUCCESS");
        assertThat(status()).isEqualTo("DONE");

        worker.runDue(later(2));

        assertThat(outcomes(AuditAction.ACCOUNT_DISABLED)).as("closed once, never twice")
                .containsExactly("ATTEMPTED", "SUCCESS");
        verify(keycloak, times(2)).setEnabled(id, false);
    }

    @Test
    void anOperationInterruptedAfterKeycloakIsClosedOnceByTheWorker() {
        // The process died after Keycloak was changed and before anything closed the operation: all that is left is
        // the pending record and its ATTEMPTED entry
        AccountOperation interrupted = AccountOperation.statusChange(id, false)
                .requestedBy(UUID.randomUUID(), "10.0.0.1");
        store.insert(interrupted, Instant.now().minusSeconds(3600));
        store.appendAudit(interrupted.id(), interrupted.action(), id, interrupted.requestedBy(),
                interrupted.ipAddress(), AuditOutcome.ATTEMPTED);

        worker.runDue(Instant.now());

        assertThat(outcomes(AuditAction.ACCOUNT_DISABLED)).containsExactly("ATTEMPTED", "SUCCESS");
        assertThat(jdbc.queryForObject("select user_id from audit_entries where operation_id = ? "
                + "and outcome = 'SUCCESS'", UUID.class, interrupted.id()))
                .as("the closing entry keeps the caller of the request").isEqualTo(interrupted.requestedBy());
        assertThat(status()).isEqualTo("DONE");
        verify(keycloak).setEnabled(id, false);

        worker.runDue(later(1));

        assertThat(outcomes(AuditAction.ACCOUNT_DISABLED)).containsExactly("ATTEMPTED", "SUCCESS");
    }

    @Test
    void askingAgainForTheSameChangeReusesThePendingOperation() {
        doThrow(new KeycloakUnavailableException("down")).doNothing().when(keycloak).setEnabled(id, false);
        assertThatThrownBy(this::disable).isInstanceOf(KeycloakUnavailableException.class);

        disable();

        assertThat(outcomes(AuditAction.ACCOUNT_DISABLED))
                .as("no second ATTEMPTED entry: the same operation is carried on").containsExactly("ATTEMPTED", "SUCCESS");
        assertThat(operations()).isEqualTo(1);
    }

    @Test
    void aDifferentChangeWhileOneIsPendingIsRefused() {
        doThrow(new KeycloakUnavailableException("down")).when(keycloak).setEnabled(id, false);
        assertThatThrownBy(this::disable).isInstanceOf(KeycloakUnavailableException.class);

        assertThatThrownBy(() -> admin.setEnabled(id, true, UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("OPERATION_PENDING");
                });

        verify(keycloak, never()).setEnabled(id, true);
        assertThat(outcomes(AuditAction.ACCOUNT_ENABLED)).isEmpty();
    }

    @Test
    void aRefusalIsClosedAsAFailureAndNeverRetried() {
        doThrow(new ApiException(HttpStatus.CONFLICT, "REFUSED", "refused")).when(keycloak).setEnabled(id, false);

        assertThatThrownBy(this::disable).isInstanceOf(ApiException.class);

        assertThat(outcomes(AuditAction.ACCOUNT_DISABLED)).containsExactly("ATTEMPTED", "FAILURE");
        assertThat(status()).isEqualTo("FAILED");

        worker.runDue(later(1));

        verify(keycloak, times(1)).setEnabled(id, false);
        assertThat(outcomes(AuditAction.ACCOUNT_DISABLED)).containsExactly("ATTEMPTED", "FAILURE");
    }

    @Test
    void anOperationThatKeepsFailingIsClosedAsAFailureAfterTheMaximumOfTries() {
        doThrow(new KeycloakUnavailableException("down")).when(keycloak).setEnabled(id, false);
        assertThatThrownBy(this::disable).isInstanceOf(KeycloakUnavailableException.class);

        for (int hour = 1; hour <= 10; hour++) {
            worker.runDue(later(hour));
        }

        assertThat(outcomes(AuditAction.ACCOUNT_DISABLED)).containsExactly("ATTEMPTED", "FAILURE");
        assertThat(status()).isEqualTo("FAILED");
    }

    @Test
    void anOperationHasAtMostOneClosingEntry() {
        disable();
        UUID operation = jdbc.queryForObject("select operation_id from audit_entries where resource_id = ? "
                + "and outcome = 'SUCCESS'", UUID.class, id.toString());

        assertThatThrownBy(() -> jdbc.update("insert into audit_entries (id, timestamp, action, resource_type, "
                + "resource_id, outcome, operation_id) values (?, now(), 'ACCOUNT_DISABLED', 'USER', ?, 'FAILURE', ?)",
                UUID.randomUUID(), id.toString(), operation))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void whenTheAttemptCannotBeWrittenNothingIsDoneAndNothingIsLeftPending() {
        doThrow(new IllegalStateException("audit down")).when(store)
                .appendAudit(any(), eq(AuditAction.ACCOUNT_DISABLED), any(), any(), any(),
                        eq(AuditOutcome.ATTEMPTED));

        assertThatThrownBy(this::disable)
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getCode()).isEqualTo("AUDIT_UNAVAILABLE");
                });

        verify(keycloak, never()).setEnabled(any(), anyBoolean());
        assertThat(operations()).as("the record and the entry were one transaction").isZero();
        assertThat(outcomes(AuditAction.ACCOUNT_DISABLED)).isEmpty();
    }

    @Test
    void aWorkerWhoseLeaseEndedCannotCloseWhatAnotherWorkerTookOver() {
        AccountOperation op = pending(id, Instant.now().minusSeconds(3600));
        Instant start = Instant.now();
        AccountOperation stale = store.lease(op.id(), start, 120).orElseThrow();
        AtomicReference<AccountOperation> takenOver = new AtomicReference<>();

        // The first worker is still busy when its lease ends: a second worker claims the same operation
        runner.execute(stale, running -> takenOver.set(
                store.lease(op.id(), start.plusSeconds(300), 120).orElseThrow()), start);

        assertThat(takenOver.get().leaseToken()).as("a new claim, a new token").isNotEqualTo(stale.leaseToken());
        assertThat(outcomes(AuditAction.ACCOUNT_DISABLED)).as("the stale worker closed nothing")
                .containsExactly("ATTEMPTED");
        assertThat(statusOf(op.id())).isEqualTo("PENDING");

        runner.execute(takenOver.get(), running -> { }, start.plusSeconds(301));

        assertThat(outcomes(AuditAction.ACCOUNT_DISABLED)).containsExactly("ATTEMPTED", "SUCCESS");
        assertThat(statusOf(op.id())).isEqualTo("DONE");
    }

    @Test
    void aWorkerWhoseLeaseEndedCannotPutBackWhatAnotherWorkerHolds() {
        AccountOperation op = pending(id, Instant.now().minusSeconds(3600));
        Instant start = Instant.now();
        AccountOperation stale = store.lease(op.id(), start, 120).orElseThrow();

        assertThatThrownBy(() -> runner.execute(stale, running -> {
            store.lease(op.id(), start.plusSeconds(300), 120).orElseThrow();
            throw new KeycloakUnavailableException("down");
        }, start)).isInstanceOf(KeycloakUnavailableException.class);

        assertThat(held(op.id())).as("the lease of the second worker is untouched").isTrue();
        assertThat(statusOf(op.id())).isEqualTo("PENDING");
        assertThat(store.reschedule(op.id(), stale.leaseToken(), start, "x")).isFalse();
        assertThat(store.close(op.id(), stale.leaseToken(), "DONE", start, null)).isFalse();
    }

    @Test
    void theOperationsOfABatchAreTakenOneByOneJustBeforeTheyRun() {
        UUID otherUser = UUID.randomUUID();
        AccountOperation first = pending(id, Instant.now().minusSeconds(7200));
        AccountOperation second = pending(otherUser, Instant.now().minusSeconds(7100));
        List<Boolean> secondHeldWhileFirstRuns = new ArrayList<>();

        runner.runDue(Instant.now(), running -> {
            if (running.id().equals(first.id())) {
                secondHeldWhileFirstRuns.add(held(second.id()));
            }
        });

        assertThat(secondHeldWhileFirstRuns).as("the second one waits without a lease while the first runs")
                .containsExactly(false);
        assertThat(statusOf(first.id())).isEqualTo("DONE");
        assertThat(statusOf(second.id())).isEqualTo("DONE");
    }
}
