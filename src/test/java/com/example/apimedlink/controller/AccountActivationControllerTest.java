package com.example.apimedlink.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.exception.InvalidPasswordException;
import com.example.apimedlink.model.EmailToken.Purpose;
import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.UserRepository;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.EmailTokenService;
import com.example.apimedlink.service.KeycloakAdminService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.persistence.EntityNotFoundException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Endpoint 14 (MLink-71): POST /api/auth/account/activate, and the activation mail an admin's account creation
 * sends. Real database; Keycloak and the mail sender are mocks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountActivationControllerTest {

    static final String PASSWORD = "Sel-De-Mer-2026!";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    EmailTokenService tokens;

    @MockitoBean
    KeycloakAdminService keycloak;

    @MockitoBean
    JavaMailSender mailSender;

    UUID keycloakId;
    String email;

    @BeforeEach
    void setUp() {
        keycloakId = UUID.randomUUID();
        email = "salma-" + keycloakId + "@example.com";
        when(keycloak.createUser(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(keycloakId);
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage((Session) null));
    }

    private String tokenOfAnAccount(Purpose purpose) {
        users.save(User.builder().id(keycloakId).username(email).email(email).build());
        return tokens.issue(keycloakId, purpose);
    }

    private ResultActions activate(String token, String password) throws Exception {
        return mvc.perform(post("/api/auth/account/activate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\",\"password\":\"%s\"}".formatted(token, password)));
    }

    private ResultActions createAsStaff() throws Exception {
        return mvc.perform(post("/api/admin/users").with(user("staff").roles(Roles.ADMINISTRATIVE_STAFF)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","firstName":"Salma","lastName":"Trabelsi","role":"ADMINISTRATIVE_STAFF"}
                        """.formatted(email)));
    }

    @Test
    void aValidTokenSetsTheFirstPasswordAndVerifiesTheEmail() throws Exception {
        String token = tokenOfAnAccount(Purpose.ACTIVATE_ACCOUNT);

        activate(token, PASSWORD).andExpect(status().isNoContent());

        verify(keycloak).setPassword(keycloakId, PASSWORD);
        verify(keycloak).setEmailVerified(keycloakId, true);
    }

    @Test
    void anUnknownTokenIsInvalid() throws Exception {
        activate("x".repeat(43), PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));

        verify(keycloak, never()).setPassword(any(), anyString());
    }

    @Test
    void aTokenWorksOnlyOnce() throws Exception {
        String token = tokenOfAnAccount(Purpose.ACTIVATE_ACCOUNT);

        activate(token, PASSWORD).andExpect(status().isNoContent());
        activate(token, PASSWORD)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    void aVerificationTokenIsNotAnActivationToken() throws Exception {
        String token = tokenOfAnAccount(Purpose.VERIFY_EMAIL);

        activate(token, PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));

        verify(keycloak, never()).setPassword(any(), anyString());
    }

    @Test
    void aPasswordRefusedByThePolicyIsExplainedAndTheTokenStaysUsable() throws Exception {
        String token = tokenOfAnAccount(Purpose.ACTIVATE_ACCOUNT);
        doThrow(new InvalidPasswordException("invalidPasswordMinLengthMessage", "minimum length 12."))
                .when(keycloak).setPassword(keycloakId, "short");

        activate(token, "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_POLICY"))
                .andExpect(jsonPath("$.details.password").isNotEmpty());

        activate(token, PASSWORD).andExpect(status().isNoContent());
        verify(keycloak).setEmailVerified(keycloakId, true);
    }

    @Test
    void anAccountDeletedInKeycloakMakesTheLinkInvalid() throws Exception {
        String token = tokenOfAnAccount(Purpose.ACTIVATE_ACCOUNT);
        doThrow(new EntityNotFoundException("gone")).when(keycloak).setPassword(keycloakId, PASSWORD);

        activate(token, PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void aMissingPasswordIsRefused() throws Exception {
        mvc.perform(post("/api/auth/account/activate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.password").isNotEmpty());
    }

    @Test
    void withoutTheCsrfHeaderItIsRefused() throws Exception {
        mvc.perform(post("/api/auth/account/activate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"abc\",\"password\":\"%s\"}".formatted(PASSWORD)))
                .andExpect(status().isForbidden());

        verify(keycloak, never()).setPassword(any(), anyString());
    }

    @Test
    void anAccountCreatedByStaffIsActivatedWithTheMailedLink() throws Exception {
        createAsStaff().andExpect(status().isCreated());

        ArgumentCaptor<MimeMessage> mail = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(mail.capture());
        assertThat(mail.getValue().getAllRecipients()[0].toString()).isEqualTo(email);
        assertThat(mail.getValue().getSubject()).isEqualTo("Activez votre compte MedLink");
        String text = EmailVerificationControllerTest.textOf(mail.getValue());
        Matcher found = Pattern.compile("http://localhost:3000/activer-compte\\?token=([A-Za-z0-9_-]+)").matcher(text);
        assertThat(found.find()).as("link in the mail").isTrue();

        String token = found.group(1);
        activate(token, PASSWORD).andExpect(status().isNoContent());
        verify(keycloak).setPassword(keycloakId, PASSWORD);
        verify(keycloak).setEmailVerified(keycloakId, true);
    }

    @Test
    void aMailFailureStillCreatesTheAccount() throws Exception {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));

        createAsStaff().andExpect(status().isCreated());

        assertThat(users.findById(keycloakId)).isPresent();
    }
}
