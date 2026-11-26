package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.Patient;
import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.AccountOperationStore;
import com.example.apimedlink.repository.PatientRepository;
import com.example.apimedlink.repository.UserRepository;
import com.example.apimedlink.security.Roles;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MLink-72 (reviews of #121 and #122): no account change without its audit entry, and no silent gap. A new account
 * is saved with its entry in one transaction. A role or status change is a durable operation: its record and its
 * ATTEMPTED entry are written together before anything is touched, and it is closed with its SUCCESS or FAILURE
 * entry; when an entry cannot be written the answer is a 503, never a silent success. The outages, the retries by
 * the worker and the correlation are in {@link AccountOperationTest}. Real database; Keycloak is a mock.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AdminUserAuditTest {

    @Autowired
    AdminUserService admin;

    @Autowired
    UserRepository users;

    @Autowired
    PatientRepository patients;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    @MockitoBean
    KeycloakAdminService keycloak;

    @MockitoBean
    JavaMailSender mailSender;

    @MockitoSpyBean
    AuditService auditService;

    @MockitoSpyBean
    AccountOperationStore operationStore;

    UUID keycloakId;
    String email;

    @BeforeEach
    void setUp() {
        keycloakId = UUID.randomUUID();
        email = "audit-" + keycloakId + "@example.com";
        when(keycloak.createUser(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(keycloakId);
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage((Session) null));
    }

    private void saveUser() {
        users.save(User.builder().id(keycloakId).username(email).email(email).build());
    }

    /** The outcomes written for this account and this action, oldest first. */
    private List<String> outcomes(AuditAction action) {
        return jdbc.queryForList("select outcome from audit_entries where resource_id = ? and action = ? "
                + "order by timestamp", String.class, keycloakId.toString(), action.name());
    }

    private String operationStatus() {
        return jdbc.queryForObject("select status from account_operations where user_id = ? "
                + "order by created_at desc limit 1", String.class, keycloakId);
    }

    private long failedCreations() {
        Long count = jdbc.queryForObject("select count(*) from audit_entries where resource_id = 'NEW_ACCOUNT' "
                + "and action = 'ACCOUNT_CREATED' and outcome = 'FAILURE'", Long.class);
        return count == null ? 0 : count;
    }

    // ---- creation: the entry is saved with the account, in one transaction ----

    @Test
    void aCreatedAccountIsSavedWithItsAuditEntry() {
        admin.create(email, "Salma", "Trabelsi", Roles.ADMINISTRATIVE_STAFF, null, null);

        assertThat(users.existsById(keycloakId)).isTrue();
        assertThat(outcomes(AuditAction.ACCOUNT_CREATED)).containsExactly("SUCCESS");
    }

    @Test
    void anAccountWhoseAuditEntryCannotBeWrittenIsNotCreatedAtAll() {
        // recordWithin is MANDATORY: even to prepare the fake failure, the call must happen inside a transaction
        new TransactionTemplate(txManager).executeWithoutResult(status ->
                doThrow(new IllegalStateException("audit down")).when(auditService)
                        .recordWithin(eq(AuditAction.ACCOUNT_CREATED), anyString(), anyString(),
                                any(AuditOutcome.class)));

        assertThatThrownBy(() -> admin.create(email, "Salma", "Trabelsi", Roles.ADMINISTRATIVE_STAFF, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit down");

        // The rows and the entry were one transaction: none is kept, and the Keycloak user is deleted again
        assertThat(users.existsById(keycloakId)).isFalse();
        assertThat(outcomes(AuditAction.ACCOUNT_CREATED)).isEmpty();
        verify(keycloak).deleteUser(keycloakId);
    }

    @Test
    void aRefusedCreationIsAuditedAsANewAccount() {
        saveUser();
        long before = failedCreations();

        assertThatThrownBy(() -> admin.create(email, "Salma", "Trabelsi", Roles.ADMINISTRATIVE_STAFF, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("EMAIL_TAKEN"));

        assertThat(failedCreations()).isEqualTo(before + 1);
    }

    // ---- role change: traced, then sealed ----

    @Test
    void aRoleChangeIsTracedThenSealed() {
        saveUser();

        admin.changeRole(keycloakId, Roles.ADMINISTRATIVE_STAFF, null, null);

        assertThat(outcomes(AuditAction.ROLE_CHANGED)).containsExactly("ATTEMPTED", "SUCCESS");
        assertThat(operationStatus()).isEqualTo("DONE");
    }

    @Test
    void aRefusedRoleChangeIsAuditedAndNeverAttempted() {
        Patient patient = new Patient("Amel", "Ben Salah", LocalDate.of(1990, 1, 1), null, null);
        patient.setId(keycloakId);
        patient.setUsername(email);
        patient.setEmail(email);
        patients.save(patient);

        assertThatThrownBy(() -> admin.changeRole(keycloakId, Roles.PRACTITIONER, null, null))
                .isInstanceOf(ApiException.class);

        assertThat(outcomes(AuditAction.ROLE_CHANGED)).containsExactly("FAILURE");
    }

    // ---- enable and disable: traced, then sealed ----

    @Test
    void disablingAnAccountIsTracedThenSealed() {
        saveUser();

        admin.setEnabled(keycloakId, false, UUID.randomUUID());

        assertThat(outcomes(AuditAction.ACCOUNT_DISABLED)).containsExactly("ATTEMPTED", "SUCCESS");
        verify(keycloak).setEnabled(keycloakId, false);
    }

    @Test
    void enablingAnAccountIsTracedThenSealed() {
        admin.setEnabled(keycloakId, true, UUID.randomUUID());

        assertThat(outcomes(AuditAction.ACCOUNT_ENABLED)).containsExactly("ATTEMPTED", "SUCCESS");
        verify(keycloak).setEnabled(keycloakId, true);
    }

    @Test
    void disablingYourOwnAccountIsRefusedAndAudited() {
        assertThatThrownBy(() -> admin.setEnabled(keycloakId, false, keycloakId))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CANNOT_DISABLE_SELF"));

        assertThat(outcomes(AuditAction.ACCOUNT_DISABLED)).containsExactly("FAILURE");
    }

    @Test
    void aFailureInKeycloakLeavesTheOperationPendingForARetry() {
        saveUser();
        doThrow(new IllegalStateException("keycloak down")).when(keycloak).setEnabled(keycloakId, false);

        assertThatThrownBy(() -> admin.setEnabled(keycloakId, false, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("keycloak down");

        // Not closed: an outage is not a refusal. The operation stays pending and the worker carries it out again
        assertThat(outcomes(AuditAction.ACCOUNT_DISABLED)).containsExactly("ATTEMPTED");
        assertThat(operationStatus()).isEqualTo("PENDING");
    }

    // ---- no trace, no change; no silent success ----

    @Test
    void whenTheAttemptCannotBeWrittenNothingIsDone() {
        saveUser();
        doThrow(new IllegalStateException("audit down")).when(operationStore)
                .appendAudit(any(), eq(AuditAction.ACCOUNT_DISABLED), any(), any(), any(),
                        eq(AuditOutcome.ATTEMPTED));

        assertThatThrownBy(() -> admin.setEnabled(keycloakId, false, UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getCode()).isEqualTo("AUDIT_UNAVAILABLE");
                });

        verify(keycloak, never()).setEnabled(any(), anyBoolean());
        verify(keycloak, never()).endSessions(any());
        assertThat(outcomes(AuditAction.ACCOUNT_DISABLED)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from account_operations where user_id = ?", Long.class,
                keycloakId)).as("the record and its entry were one transaction").isZero();
    }

    @Test
    void whenTheClosingEntryCannotBeWrittenTheAnswerIsAnErrorAndTheSameRequestCompletesIt() {
        saveUser();
        doThrow(new IllegalStateException("audit down")).when(operationStore)
                .appendAudit(any(), eq(AuditAction.ACCOUNT_DISABLED), any(), any(), any(),
                        eq(AuditOutcome.SUCCESS));

        assertThatThrownBy(() -> admin.setEnabled(keycloakId, false, UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getCode()).isEqualTo("AUDIT_UNAVAILABLE");
                });

        // Never a silent success: the change was made, the trail shows the attempt, and the caller was told.
        // The operation is still pending: nothing was closed.
        verify(keycloak).setEnabled(keycloakId, false);
        assertThat(outcomes(AuditAction.ACCOUNT_DISABLED)).containsExactly("ATTEMPTED");
        assertThat(operationStatus()).isEqualTo("PENDING");

        // The audit store is back: the same request carries on the same operation, and the trail is closed
        doCallRealMethod().when(operationStore)
                .appendAudit(any(), eq(AuditAction.ACCOUNT_DISABLED), any(), any(), any(),
                        eq(AuditOutcome.SUCCESS));
        admin.setEnabled(keycloakId, false, UUID.randomUUID());

        assertThat(outcomes(AuditAction.ACCOUNT_DISABLED)).containsExactly("ATTEMPTED", "SUCCESS");
        assertThat(operationStatus()).isEqualTo("DONE");
        verify(keycloak, times(2)).setEnabled(keycloakId, false);
    }

    @Test
    void aRefusalWhoseEntryCannotBeWrittenKeepsTheOriginalError() {
        doThrow(new IllegalStateException("audit down")).when(auditService)
                .record(eq(AuditAction.ACCOUNT_DISABLED), anyString(), anyString(), eq(AuditOutcome.FAILURE));

        assertThatThrownBy(() -> admin.setEnabled(keycloakId, false, keycloakId))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CANNOT_DISABLE_SELF"))
                .hasSuppressedException(new IllegalStateException("audit down"));
    }
}
