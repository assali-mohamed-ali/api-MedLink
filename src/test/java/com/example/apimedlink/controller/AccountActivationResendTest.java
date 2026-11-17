package com.example.apimedlink.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.exception.KeycloakUnavailableException;
import com.example.apimedlink.model.EmailToken.Purpose;
import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.UserRepository;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.EmailTokenService;
import com.example.apimedlink.service.KeycloakAdminService;
import com.example.apimedlink.service.KeycloakUser;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Endpoint 15 (MLink-71): POST /api/auth/account/activate/resend. When the first activation mail fails or its link
 * expires, a new link is mailed, without telling whether an account exists. Real database; Keycloak and the mail
 * sender are mocks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountActivationResendTest {

    static final String PASSWORD = "Sel-De-Mer-2026!";
    static final String RESEND_MESSAGE =
            "Si un compte en attente d'activation existe pour cet e-mail, un nouveau lien a été envoyé.";
    static final Pattern LINK = Pattern.compile("http://localhost:3000/activer-compte\\?token=([A-Za-z0-9_-]+)");

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    EmailTokenService tokens;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    KeycloakAdminService keycloak;

    @MockitoBean
    JavaMailSender mailSender;

    UUID userId;
    String email;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        email = "salma-" + userId + "@example.com";
        when(keycloak.createUser(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(userId);
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage((Session) null));
    }

    private ResultActions resend(String address) throws Exception {
        return mvc.perform(post("/api/auth/account/activate/resend").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + address + "\"}"));
    }

    private ResultActions activate(String token) throws Exception {
        return mvc.perform(post("/api/auth/account/activate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\",\"password\":\"%s\"}".formatted(token, PASSWORD)));
    }

    private ResultActions createAsStaff() throws Exception {
        return mvc.perform(post("/api/admin/users").with(user("staff").roles(Roles.ADMINISTRATIVE_STAFF)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","firstName":"Salma","lastName":"Trabelsi","role":"ADMINISTRATIVE_STAFF"}
                        """.formatted(email)));
    }

    /** An account whose last link of this kind was mailed two minutes ago (past the one-a-minute limit). */
    private String accountWithOlderToken(Purpose purpose) {
        users.save(User.builder().id(userId).username(email).email(email).build());
        String raw = tokens.issue(userId, purpose);
        jdbc.update("update email_tokens set created_at = now() - interval '2 minutes' where user_id = ?", userId);
        return raw;
    }

    private void keycloakKnows(boolean enabled, boolean verified) {
        when(keycloak.getUser(userId)).thenReturn(new KeycloakUser(userId, email, "Salma", "Trabelsi", enabled, verified));
    }

    /** The token in the last mail sent. */
    private String tokenInLastMail(int mailsSent) throws Exception {
        ArgumentCaptor<MimeMessage> mail = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, times(mailsSent)).send(mail.capture());
        List<MimeMessage> all = mail.getAllValues();
        MimeMessage last = all.get(all.size() - 1);
        assertThat(last.getAllRecipients()[0].toString()).isEqualTo(email);
        assertThat(last.getSubject()).isEqualTo("Activez votre compte MedLink");
        Matcher found = LINK.matcher(EmailVerificationControllerTest.textOf(last));
        assertThat(found.find()).as("link in the mail").isTrue();
        return found.group(1);
    }

    @Test
    void afterAMailFailureAtCreationAResendMailsALinkThatActivatesTheAccount() throws Exception {
        doThrow(new MailSendException("smtp down")).doNothing().when(mailSender).send(any(MimeMessage.class));
        createAsStaff().andExpect(status().isCreated()); // the account exists, its first mail was lost
        jdbc.update("update email_tokens set created_at = now() - interval '2 minutes' where user_id = ?", userId);
        keycloakKnows(true, false);

        resend(email).andExpect(status().isAccepted()).andExpect(jsonPath("$.message").value(RESEND_MESSAGE));

        String token = tokenInLastMail(2);
        activate(token).andExpect(status().isNoContent());
        verify(keycloak).setPassword(userId, PASSWORD);
        verify(keycloak).setEmailVerified(userId, true);
    }

    @Test
    void anExpiredLinkIsReplacedByANewOne() throws Exception {
        String expired = accountWithOlderToken(Purpose.ACTIVATE_ACCOUNT);
        jdbc.update("update email_tokens set expires_at = now() - interval '1 second' where user_id = ?", userId);
        keycloakKnows(true, false);
        activate(expired).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));

        resend(email).andExpect(status().isAccepted());

        activate(tokenInLastMail(1)).andExpect(status().isNoContent());
    }

    @Test
    void theOldLinkStopsWorkingWhenANewOneIsSent() throws Exception {
        String old = accountWithOlderToken(Purpose.ACTIVATE_ACCOUNT);
        keycloakKnows(true, false);

        resend(email.toUpperCase()).andExpect(status().isAccepted());

        activate(old).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
        activate(tokenInLastMail(1)).andExpect(status().isNoContent());
    }

    @Test
    void atMostOneMailPerMinute() throws Exception {
        accountWithOlderToken(Purpose.ACTIVATE_ACCOUNT);
        keycloakKnows(true, false);

        resend(email).andExpect(status().isAccepted());
        resend(email).andExpect(status().isAccepted());

        verify(mailSender).send(any(MimeMessage.class)); // once
    }

    @Test
    void aLinkMailedLessThanAMinuteAgoIsNotResent() throws Exception {
        users.save(User.builder().id(userId).username(email).email(email).build());
        tokens.issue(userId, Purpose.ACTIVATE_ACCOUNT);
        keycloakKnows(true, false);

        resend(email).andExpect(status().isAccepted()).andExpect(jsonPath("$.message").value(RESEND_MESSAGE));

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void anUnknownEmailGetsTheSameAnswerAndNoMail() throws Exception {
        resend("nobody@example.com").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(RESEND_MESSAGE));

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void anAccountNotCreatedByStaffGetsNoMail() throws Exception {
        accountWithOlderToken(Purpose.VERIFY_EMAIL); // a patient waiting for their verification link
        keycloakKnows(true, false);

        resend(email).andExpect(status().isAccepted()).andExpect(jsonPath("$.message").value(RESEND_MESSAGE));

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void anActivatedAccountGetsNoMail() throws Exception {
        accountWithOlderToken(Purpose.ACTIVATE_ACCOUNT);
        keycloakKnows(true, true);

        resend(email).andExpect(status().isAccepted()).andExpect(jsonPath("$.message").value(RESEND_MESSAGE));

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void aDisabledAccountGetsNoMail() throws Exception {
        accountWithOlderToken(Purpose.ACTIVATE_ACCOUNT);
        keycloakKnows(false, false);

        resend(email).andExpect(status().isAccepted()).andExpect(jsonPath("$.message").value(RESEND_MESSAGE));

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void whenKeycloakIsDownTheAnswerIsTheSame() throws Exception {
        accountWithOlderToken(Purpose.ACTIVATE_ACCOUNT);
        when(keycloak.getUser(userId)).thenThrow(new KeycloakUnavailableException("down"));

        resend(email).andExpect(status().isAccepted()).andExpect(jsonPath("$.message").value(RESEND_MESSAGE));

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void aMailFailureOnResendIsOnlyLogged() throws Exception {
        accountWithOlderToken(Purpose.ACTIVATE_ACCOUNT);
        keycloakKnows(true, false);
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));

        resend(email).andExpect(status().isAccepted()).andExpect(jsonPath("$.message").value(RESEND_MESSAGE));
    }

    @Test
    void withoutTheCsrfHeaderItIsRefused() throws Exception {
        mvc.perform(post("/api/auth/account/activate/resend").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isForbidden());

        verify(mailSender, never()).send(any(MimeMessage.class));
    }
}
