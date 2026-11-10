package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.Patient;
import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.PatientRepository;
import com.example.apimedlink.repository.UserRepository;
import com.example.apimedlink.security.Roles;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** MLink-72: every account operation, done or refused, leaves an audit entry. Real database; Keycloak is a mock. */
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

    @MockitoBean
    KeycloakAdminService keycloak;

    @MockitoBean
    JavaMailSender mailSender;

    @MockitoSpyBean
    AuditService auditService;

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

    private List<Map<String, Object>> entriesFor(UUID id) {
        return jdbc.queryForList(
                "select action, outcome, resource_type from audit_entries where resource_id = ?", id.toString());
    }

    private long failedCreations() {
        Long count = jdbc.queryForObject("select count(*) from audit_entries where resource_id = 'NEW_ACCOUNT' "
                + "and action = 'ACCOUNT_CREATED' and outcome = 'FAILURE'", Long.class);
        return count == null ? 0 : count;
    }

    private void assertSingleEntry(UUID id, AuditAction action, AuditOutcome outcome) {
        List<Map<String, Object>> entries = entriesFor(id);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0)).containsEntry("action", action.name())
                .containsEntry("outcome", outcome.name())
                .containsEntry("resource_type", "USER");
    }

    @Test
    void aCreatedAccountIsAudited() {
        admin.create(email, "Salma", "Trabelsi", Roles.ADMINISTRATIVE_STAFF, null, null);

        assertSingleEntry(keycloakId, AuditAction.ACCOUNT_CREATED, AuditOutcome.SUCCESS);
    }

    @Test
    void aRefusedCreationIsAuditedAsANewAccount() {
        saveUser();
        long before = failedCreations();

        assertThatThrownBy(() -> admin.create(email, "Salma", "Trabelsi", Roles.ADMINISTRATIVE_STAFF, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("EMAIL_TAKEN"));

        assertThat(failedCreations()).isEqualTo(before + 1);
    }

    @Test
    void aRoleChangeIsAudited() {
        saveUser();

        admin.changeRole(keycloakId, Roles.ADMINISTRATIVE_STAFF, null, null);

        assertSingleEntry(keycloakId, AuditAction.ROLE_CHANGED, AuditOutcome.SUCCESS);
    }

    @Test
    void aRefusedRoleChangeIsAudited() {
        Patient patient = new Patient("Amel", "Ben Salah", LocalDate.of(1990, 1, 1), null, null);
        patient.setId(keycloakId);
        patient.setUsername(email);
        patient.setEmail(email);
        patients.save(patient);

        assertThatThrownBy(() -> admin.changeRole(keycloakId, Roles.PRACTITIONER, null, null))
                .isInstanceOf(ApiException.class);

        assertSingleEntry(keycloakId, AuditAction.ROLE_CHANGED, AuditOutcome.FAILURE);
    }

    @Test
    void disablingAnAccountIsAudited() {
        saveUser();

        admin.setEnabled(keycloakId, false, UUID.randomUUID());

        assertSingleEntry(keycloakId, AuditAction.ACCOUNT_DISABLED, AuditOutcome.SUCCESS);
    }

    @Test
    void enablingAnAccountIsAudited() {
        admin.setEnabled(keycloakId, true, UUID.randomUUID());

        assertSingleEntry(keycloakId, AuditAction.ACCOUNT_ENABLED, AuditOutcome.SUCCESS);
    }

    @Test
    void disablingYourOwnAccountIsRefusedAndAudited() {
        assertThatThrownBy(() -> admin.setEnabled(keycloakId, false, keycloakId))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CANNOT_DISABLE_SELF"));

        assertSingleEntry(keycloakId, AuditAction.ACCOUNT_DISABLED, AuditOutcome.FAILURE);
    }

    @Test
    void anAuditWriteFailureDoesNotBreakTheOperation() {
        saveUser();
        doThrow(new IllegalStateException("audit down")).when(auditService)
                .record(any(AuditAction.class), anyString(), anyString(), any(AuditOutcome.class));

        admin.setEnabled(keycloakId, false, UUID.randomUUID());

        verify(keycloak).setEnabled(keycloakId, false);
    }
}
