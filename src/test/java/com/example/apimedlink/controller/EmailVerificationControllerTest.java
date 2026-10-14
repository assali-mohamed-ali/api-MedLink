package com.example.apimedlink.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.exception.KeycloakUnavailableException;
import com.example.apimedlink.model.EmailToken;
import com.example.apimedlink.model.EmailToken.Purpose;
import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.EmailTokenRepository;
import com.example.apimedlink.repository.UserRepository;
import com.example.apimedlink.service.EmailTokenService;
import com.example.apimedlink.service.KeycloakAdminService;
import com.example.apimedlink.service.KeycloakUser;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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

/** Endpoints 3 and 4 (plan.md §4): POST /api/auth/verify-email and /verify-email/resend. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EmailVerificationControllerTest {

    static final String RESEND_MESSAGE = "Si un compte non vérifié existe pour cet e-mail, un nouveau lien a été envoyé.";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    EmailTokenRepository tokenRows;

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
        email = "p-" + userId + "@example.com";
        users.save(User.builder().id(userId).username(email).email(email).build());
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage((Session) null));
    }

    private ResultActions postJson(String url, String json) throws Exception {
        return mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions verifyEmail(String token) throws Exception {
        return postJson("/api/auth/verify-email", "{\"token\":\"" + token + "\"}");
    }

    private ResultActions resend(String address) throws Exception {
        return postJson("/api/auth/verify-email/resend", "{\"email\":\"" + address + "\"}");
    }

    private void unverifiedInKeycloak() {
        when(keycloak.getUser(userId)).thenReturn(new KeycloakUser(userId, email, "Amel", "Ben Salah", true, false));
    }

    /** Pretends the user's last link was mailed two minutes ago (past the one-per-minute limit). */
    private String olderToken() {
        String raw = tokens.issue(userId, Purpose.VERIFY_EMAIL);
        jdbc.update("update email_tokens set created_at = now() - interval '2 minutes' where user_id = ?", userId);
        return raw;
    }

    // ---- verify-email ------------------------------------------------------------------------

    @Test
    void aValidTokenVerifiesTheEmailInKeycloak() throws Exception {
        String raw = tokens.issue(userId, Purpose.VERIFY_EMAIL);

        verifyEmail(raw).andExpect(status().isNoContent());

        verify(keycloak).setEmailVerified(userId, true);
        verifyEmail(raw).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    void whenKeycloakFailsTheTokenStaysUsable() throws Exception {
        String raw = tokens.issue(userId, Purpose.VERIFY_EMAIL);
        doThrow(new KeycloakUnavailableException("down")).when(keycloak).setEmailVerified(userId, true);

        verifyEmail(raw).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDENTITY_UNAVAILABLE"));

        EmailToken row = tokenRows.findByTokenHash(hashOf(raw)).orElseThrow();
        assertThat(row.getUsedAt()).isNull();
    }

    @Test
    void anUnknownTokenIsInvalid() throws Exception {
        verifyEmail("unknown-token").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        verify(keycloak, never()).setEmailVerified(any(), anyBoolean());
    }

    @Test
    void anExpiredTokenIsGone() throws Exception {
        String raw = tokens.issue(userId, Purpose.VERIFY_EMAIL);
        jdbc.update("update email_tokens set expires_at = now() - interval '1 second' where user_id = ?", userId);

        verifyEmail(raw).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    void aBlankTokenIsAValidationError() throws Exception {
        verifyEmail("").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.token").isNotEmpty());
    }

    @Test
    void withoutTheCsrfHeaderItIsRefused() throws Exception {
        mvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    // ---- verify-email/resend -----------------------------------------------------------------

    @Test
    void anUnknownEmailGetsTheSameAnswerAndNoMail() throws Exception {
        resend("nobody@example.com").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(RESEND_MESSAGE));

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void aVerifiedAccountGetsNoMail() throws Exception {
        when(keycloak.getUser(userId)).thenReturn(new KeycloakUser(userId, email, "Amel", "Ben Salah", true, true));

        resend(email).andExpect(status().isAccepted()).andExpect(jsonPath("$.message").value(RESEND_MESSAGE));

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void anUnverifiedAccountGetsANewLinkAndTheOldOneStopsWorking() throws Exception {
        String old = olderToken();
        unverifiedInKeycloak();

        resend(email.toUpperCase()).andExpect(status().isAccepted()).andExpect(jsonPath("$.message").value(RESEND_MESSAGE));

        MimeMessage mail = sentMail();
        assertThat(mail.getAllRecipients()[0].toString()).isEqualTo(email);
        assertThat(mail.getSubject()).isEqualTo("Confirmez votre adresse e-mail MedLink");
        String text = textOf(mail);
        assertThat(text).contains("Bonjour Amel,");
        String link = linkIn(text);
        assertThat(link).startsWith("http://localhost:3000/verifier-email?token=");

        String fresh = link.substring(link.indexOf("token=") + 6);
        verifyEmail(old).andExpect(status().isGone());
        verifyEmail(fresh).andExpect(status().isNoContent());
    }

    @Test
    void atMostOneMailPerMinute() throws Exception {
        olderToken();
        unverifiedInKeycloak();

        resend(email).andExpect(status().isAccepted());
        resend(email).andExpect(status().isAccepted());

        verify(mailSender).send(any(MimeMessage.class)); // once
    }

    @Test
    void whenKeycloakIsDownTheAnswerIsTheSame() throws Exception {
        olderToken();
        when(keycloak.getUser(userId)).thenThrow(new KeycloakUnavailableException("down"));

        resend(email).andExpect(status().isAccepted()).andExpect(jsonPath("$.message").value(RESEND_MESSAGE));

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void aMailFailureIsOnlyLogged() throws Exception {
        olderToken();
        unverifiedInKeycloak();
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));

        resend(email).andExpect(status().isAccepted()).andExpect(jsonPath("$.message").value(RESEND_MESSAGE));
    }

    // ---- helpers -----------------------------------------------------------------------------

    private MimeMessage sentMail() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    /** All text parts of the mail (plain and HTML), decoded. */
    static String textOf(Part part) throws Exception {
        Object content = part.getContent();
        if (content instanceof String s) {
            return s;
        }
        StringBuilder text = new StringBuilder();
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                text.append(textOf(multipart.getBodyPart(i))).append('\n');
            }
        }
        return text.toString();
    }

    static String linkIn(String text) {
        Matcher m = Pattern.compile("http://localhost:3000/verifier-email\\?token=[A-Za-z0-9_-]+").matcher(text);
        assertThat(m.find()).as("link in the mail").isTrue();
        return m.group();
    }

    private static String hashOf(String raw) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
