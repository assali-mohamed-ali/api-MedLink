package com.example.apimedlink.document;

import static com.example.apimedlink.functional.DocumentTestFiles.PDF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.exception.AuditUnavailableException;
import com.example.apimedlink.exception.GlobalExceptionHandler;
import com.example.apimedlink.model.Patient;
import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.PatientDocumentRepository;
import com.example.apimedlink.repository.PatientRepository;
import com.example.apimedlink.repository.UserRepository;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.AuditService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * MLink-123/125 failure paths that a functional test cannot trigger on demand: an audit outage, an
 * object deleted behind the API's back, and a tampered object. This class has its own MinIO so it can
 * delete or overwrite objects without touching the shared functional-test instance. The audit and
 * storage beans are spies: their real implementations run unless a test makes them fail.
 */
@SpringBootTest
// This class proves the exception-to-503 mapping, not the access rules. Leaving the security filter
// chain on would run SessionValidityFilter, which may call Keycloak, and this test starts no Keycloak.
@AutoConfigureMockMvc(addFilters = false)
@Import(TestcontainersConfiguration.class)
class PatientDocumentFailureIntegrationTest {

    static final GenericContainer<?> MINIO = TestcontainersConfiguration.newMinioContainer();

    @DynamicPropertySource
    static void documentProperties(DynamicPropertyRegistry registry) {
        MINIO.start();
        registry.add("app.documents.storage.endpoint", () -> TestcontainersConfiguration.endpoint(MINIO));
        registry.add("app.documents.storage.access-key", () -> TestcontainersConfiguration.MINIO_ACCESS_KEY);
        registry.add("app.documents.storage.secret-key", () -> TestcontainersConfiguration.MINIO_SECRET_KEY);
        registry.add("app.documents.storage.bucket", () -> TestcontainersConfiguration.MINIO_BUCKET);
        registry.add("app.documents.storage.create-bucket", () -> true);
        registry.add("app.documents.storage.require-tls", () -> false);
    }

    @Autowired
    DocumentService documents;

    @Autowired
    PatientDocumentRepository repository;

    @Autowired
    UserRepository users;

    @Autowired
    PatientRepository patients;

    @Autowired
    GlobalExceptionHandler handler;

    @Autowired
    MockMvc mvc;

    @Autowired
    TransactionTemplate transactions;

    @MockitoSpyBean
    DocumentStorage storage;

    @MockitoSpyBean
    AuditService audit;

    UUID staffId;
    UUID patientId;

    @BeforeEach
    void setUp() {
        staffId = UUID.randomUUID();
        users.saveAndFlush(User.builder()
                .id(staffId)
                .username("staff-" + staffId + "@example.com")
                .email("staff-" + staffId + "@example.com")
                .build());
        patientId = UUID.randomUUID();
        Patient patient = new Patient("Test", "Patient", LocalDate.of(1990, 1, 1), null, null);
        patient.setId(patientId);
        patient.setUsername("patient-" + patientId + "@example.com");
        patient.setEmail("patient-" + patientId + "@example.com");
        patients.saveAndFlush(patient);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                staffId.toString(), null, "ROLE_" + Roles.ADMINISTRATIVE_STAFF));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        reset(storage, audit);
    }

    @Test
    void anAuditFailureOnUploadRollsBackTheMetadataDeletesTheObjectAndRethrows() {
        // recordInCurrentTransaction is MANDATORY: stubbing it through the Spring proxy needs a
        // transaction, otherwise the proxy's MANDATORY check fires before Mockito can record the stub.
        transactions.executeWithoutResult(status -> doThrow(new AuditUnavailableException("audit down"))
                .when(audit).recordInCurrentTransaction(any(), anyString(), anyString(), any()));

        assertThatThrownBy(() -> documents.upload(patientId, "Compte rendu", file()))
                .isInstanceOfSatisfying(AuditUnavailableException.class, failure -> {
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(failure.getCode()).isEqualTo("AUDIT_UNAVAILABLE");
                });

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).store(key.capture(), any(byte[].class));
        verify(storage).delete(key.getValue());
        assertThat(storage.exists(key.getValue())).as("the object is gone").isFalse();
        assertThat(repository.findByPatientId(patientId, PageRequest.of(0, 10)).getTotalElements())
                .as("the metadata rolled back").isZero();
    }

    @Test
    void anAuditFailureOnDownloadReturnsNoDocumentBytes() throws Exception {
        PatientDocument document = documents.upload(patientId, "Compte rendu", file());
        // A read is audited in its own transaction (AuditService.record), unlike the upload entry.
        doThrow(new AuditUnavailableException("audit down"))
                .when(audit).record(any(), anyString(), anyString(), any());

        assertThatThrownBy(() -> documents.download(patientId, document.getId()))
                .isInstanceOf(AuditUnavailableException.class);

        MvcResult result = mvc.perform(get("/api/patients/{patientId}/documents/{documentId}",
                                patientId, document.getId())
                        .with(user(staffId.toString()).roles(Roles.ADMINISTRATIVE_STAFF)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"))
                .andReturn();
        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.ISO_8859_1);
        assertThat(body).as("no document bytes in an audit failure").doesNotContain("%PDF");
    }

    @Test
    void aMissingObjectIsStorageUnavailable() {
        PatientDocument document = documents.upload(patientId, "Compte rendu", file());
        storage.delete(document.getStorageKey());

        assertThatThrownBy(() -> documents.download(patientId, document.getId()))
                .isInstanceOfSatisfying(StorageException.class, failure -> {
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(failure.getCode()).isEqualTo("STORAGE_UNAVAILABLE");
                });
    }

    @Test
    void aTamperedObjectIsAnIntegrityError() {
        PatientDocument document = documents.upload(patientId, "Compte rendu", file());
        byte[] stored = storage.load(document.getStorageKey());
        stored[stored.length - 1] ^= 1;
        storage.store(document.getStorageKey(), stored);

        assertThatThrownBy(() -> documents.download(patientId, document.getId()))
                .isInstanceOfSatisfying(DocumentIntegrityException.class, failure -> {
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(failure.getCode()).isEqualTo("INTERNAL_ERROR");
                });
    }

    @Test
    void theAuditUnavailableExceptionMapsTo503AndItsStableCode() {
        ResponseEntity<Map<String, Object>> response =
                handler.api(new AuditUnavailableException("audit down"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("status", 503).containsEntry("code", "AUDIT_UNAVAILABLE");
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "compte-rendu.pdf", "application/pdf", PDF);
    }
}
