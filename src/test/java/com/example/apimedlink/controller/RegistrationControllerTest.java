package com.example.apimedlink.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.exception.EmailTakenException;
import com.example.apimedlink.exception.InvalidPasswordException;
import com.example.apimedlink.exception.KeycloakUnavailableException;
import com.example.apimedlink.model.EmailToken.Purpose;
import com.example.apimedlink.model.Patient;
import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.EmailTokenRepository;
import com.example.apimedlink.repository.PatientRepository;
import com.example.apimedlink.repository.UserRepository;
import com.example.apimedlink.service.KeycloakAdminService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Endpoint 2 (plan.md §4), MLink-82: POST /api/auth/register, written from the contract.
 * Real database; Keycloak and the mail sender are mocks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class RegistrationControllerTest {

    static final String PASSWORD = "Sel-De-Mer-2026!";
    static final String CREATED_MESSAGE = "Vérifiez votre boîte mail pour activer votre compte.";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    PatientRepository patients;

    @Autowired
    EmailTokenRepository tokens;

    @Autowired
    TransactionTemplate tx;

    @MockitoBean
    KeycloakAdminService keycloak;

    @MockitoBean
    JavaMailSender mailSender;

    UUID keycloakId;
    String email;

    @BeforeEach
    void setUp() {
        keycloakId = UUID.randomUUID();
        email = "amel-" + keycloakId + "@example.com";
        when(keycloak.createUser(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(keycloakId);
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage((Session) null));
    }

    private String body(String emailValue, String firstName, String birthDate) {
        return """
                {"email":"%s","password":"%s","firstName":"%s","lastName":"Ben Salah",
                 "birthDate":"%s","phone":"+216 22 123 456","address":"12 rue de Carthage, Sousse"}
                """.formatted(emailValue, PASSWORD, firstName, birthDate);
    }

    private ResultActions register(String json) throws Exception {
        return mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions registerValid() throws Exception {
        return register(body(email, "Amel", "1990-04-12"));
    }

    // 1. valid body
    @Test
    void aValidBodyCreatesTheUserAndThePatientUnderKeycloaksId() throws Exception {
        registerValid().andExpect(status().isCreated()).andExpect(jsonPath("$.message").value(CREATED_MESSAGE));

        verify(keycloak).createUser(email, email, "Amel", "Ben Salah", "/patients");
        verify(keycloak).setPassword(keycloakId, PASSWORD);
        User user = users.findById(keycloakId).orElseThrow();
        assertThat(user.getEmail()).isEqualTo(email);
        assertThat(user.getUsername()).isEqualTo(email);
        tx.executeWithoutResult(s -> {
            Patient patient = patients.findById(keycloakId).orElseThrow();
            assertThat(patient.getUser().getId()).isEqualTo(keycloakId);
            assertThat(patient.getFirstName()).isEqualTo("Amel");
            assertThat(patient.getBirthDate()).isEqualTo(LocalDate.of(1990, 4, 12));
            assertThat(patient.getPhone()).isEqualTo("+216 22 123 456");
        });
        assertThat(tokens.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(keycloakId, Purpose.VERIFY_EMAIL)).isPresent();

        ArgumentCaptor<MimeMessage> mail = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(mail.capture());
        assertThat(mail.getValue().getAllRecipients()[0].toString()).isEqualTo(email);
        assertThat(EmailVerificationControllerTest.linkIn(EmailVerificationControllerTest.textOf(mail.getValue())))
                .startsWith("http://localhost:3000/verifier-email?token=");
    }

    @Test
    void theEmailIsStoredLowerCaseAndTrimmed() throws Exception {
        register(body("  " + email.toUpperCase() + " ", "Amel", "1990-04-12")).andExpect(status().isCreated());

        verify(keycloak).createUser(email, email, "Amel", "Ben Salah", "/patients");
        assertThat(users.findById(keycloakId).orElseThrow().getEmail()).isEqualTo(email);
    }

    @Test
    void aRoleOrAnIdInTheBodyIsIgnored() throws Exception {
        String json = body(email, "Amel", "1990-04-12")
                .replace("{", "{\"id\":\"" + UUID.randomUUID() + "\",\"role\":\"ADMINISTRATIVE_STAFF\",");

        register(json).andExpect(status().isCreated());

        verify(keycloak).createUser(email, email, "Amel", "Ben Salah", "/patients");
        assertThat(users.findById(keycloakId)).isPresent();
    }

    // 2. email already known
    @Test
    void anEmailAlreadyInOurDatabaseIsTakenWithoutCallingKeycloak() throws Exception {
        users.save(User.builder().id(UUID.randomUUID()).username(email).email(email).build());

        registerValid().andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"))
                .andExpect(jsonPath("$.message").value("Un compte existe déjà avec cet e-mail."));

        verify(keycloak, never()).createUser(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void anEmailKnownToKeycloakIsTakenAndNothingIsCreated() throws Exception {
        when(keycloak.createUser(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new EmailTakenException());

        registerValid().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));

        assertThat(users.findByEmail(email)).isEmpty();
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    // 3. invalid body
    @Test
    void anInvalidBodyGetsOneErrorPerField() throws Exception {
        String tomorrow = LocalDate.now().plusDays(1).toString();

        register(body("not-an-email", "", tomorrow))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.email").isNotEmpty())
                .andExpect(jsonPath("$.details.firstName").isNotEmpty())
                .andExpect(jsonPath("$.details.birthDate").isNotEmpty())
                .andExpect(jsonPath("$.details.password").doesNotExist());

        verify(keycloak, never()).createUser(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aBadPhoneIsRefused() throws Exception {
        register(body(email, "Amel", "1990-04-12").replace("+216 22 123 456", "call me maybe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.phone").isNotEmpty());
    }

    // 4. password refused by the policy
    @Test
    void aPasswordRefusedByThePolicyIsExplainedAndTheKeycloakUserDeleted() throws Exception {
        doThrow(new InvalidPasswordException("invalidPasswordMinDigitsMessage", "Invalid password: must contain at least 1 numerical digits."))
                .when(keycloak).setPassword(keycloakId, PASSWORD);

        registerValid().andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_POLICY"))
                .andExpect(jsonPath("$.details.password").value("Le mot de passe doit contenir au moins 1 chiffre."));

        verify(keycloak).deleteUser(keycloakId);
        assertThat(users.findById(keycloakId)).isEmpty();
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    // 5. database failure after the Keycloak user was created
    @Test
    void aDatabaseFailureDeletesTheKeycloakUser() throws Exception {
        // Keycloak hands out an id we already hold under another email: our insert fails
        users.save(User.builder().id(keycloakId).username("other-" + email).email("other-" + email).build());

        registerValid().andExpect(status().is5xxServerError());

        verify(keycloak).deleteUser(keycloakId);
        assertThat(patients.findById(keycloakId)).isEmpty();
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void keycloakUnreachableIsIdentityUnavailable() throws Exception {
        when(keycloak.createUser(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new KeycloakUnavailableException("down"));

        registerValid().andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("IDENTITY_UNAVAILABLE"));

        assertThat(users.findByEmail(email)).isEmpty();
    }

    @Test
    void aMailFailureStillCreatesTheAccount() throws Exception {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));

        registerValid().andExpect(status().isCreated());

        assertThat(users.findById(keycloakId)).isPresent();
    }

    // 6. public, but CSRF required
    @Test
    void withoutTheCsrfHeaderItIsRefused() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body(email, "Amel", "1990-04-12")))
                .andExpect(status().isForbidden());

        verify(keycloak, never()).createUser(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    // 7. the password never shows up
    @Test
    void thePasswordIsNeverReturnedNorLogged(CapturedOutput output) throws Exception {
        String ok = registerValid().andReturn().getResponse().getContentAsString();
        doThrow(new InvalidPasswordException("invalidPasswordMinLengthMessage", "minimum length 12."))
                .when(keycloak).setPassword(eq(keycloakId), anyString());
        String refused = register(body("x" + email, "Amel", "1990-04-12")).andReturn().getResponse().getContentAsString();
        String invalid = register(body("bad", "", "1990-04-12")).andReturn().getResponse().getContentAsString();

        assertThat(ok + refused + invalid).doesNotContain(PASSWORD);
        assertThat(output.getAll()).doesNotContain(PASSWORD);
    }
}
