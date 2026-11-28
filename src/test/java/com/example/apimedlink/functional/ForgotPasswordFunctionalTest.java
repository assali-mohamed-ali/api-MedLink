package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * US-03: "forgot my password" through the real API, Keycloak and Mailpit. The answer is the same whether or not the
 * account exists; only a verified, enabled account gets the mail; the link works once, sets the new password and ends
 * the old sessions; a weak password leaves the link usable. NOT RUN in the session that wrote it (no Docker).
 */
class ForgotPasswordFunctionalTest extends FunctionalTestBase {

    static final String OLD = "Old-Password-2026!";
    static final String NEW = "New-Password-2026!";
    static final String SUBJECT = "Réinitialisation de votre mot de passe MedLink";
    static final Pattern LINK = Pattern.compile("reinitialiser-mot-de-passe[?]token=([A-Za-z0-9_-]+)");

    final KeycloakConsole console = new KeycloakConsole();
    final Mailbox mailbox = new Mailbox();

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theAnswerIsTheSameForAnExistingAndAnUnknownAddress() throws Exception {
        String known = patientEmail();
        String unknown = uniqueEmail("nobody");

        HttpResponse<String> a = new Browser().postJson("/api/auth/password/forgot", "{\"email\":\"" + known + "\"}");
        HttpResponse<String> b = new Browser().postJson("/api/auth/password/forgot", "{\"email\":\"" + unknown + "\"}");

        assertThat(a.statusCode()).isEqualTo(202);
        assertThat(b.statusCode()).isEqualTo(202);
        assertThat(a.body()).isEqualTo(b.body());
        assertThat(mailbox.to(known, SUBJECT)).hasSize(1);
        assertThat(mailbox.to(unknown, SUBJECT)).isEmpty();
    }

    @Test
    void theLinkSetsTheNewPasswordOnceAndEndsTheOldSession() throws Exception {
        String email = patientEmail();
        Browser oldSession = new Browser();
        assertThat(oldSession.apiLogin(email, OLD).statusCode()).isEqualTo(200);
        assertThat(oldSession.call("/api/users/_health").statusCode()).isEqualTo(200);
        new Browser().postJson("/api/auth/password/forgot", "{\"email\":\"" + email + "\"}");
        String token = tokenIn(email);

        Browser visitor = new Browser();
        HttpResponse<String> reset = visitor.postJson("/api/auth/password/reset",
                "{\"token\":\"" + token + "\",\"newPassword\":\"" + NEW + "\"}");

        assertThat(reset.statusCode()).as(reset.body()).isEqualTo(204);
        assertThat(new Browser().apiLogin(email, NEW).statusCode()).isEqualTo(200);
        assertThat(new Browser().apiLogin(email, OLD).statusCode()).isEqualTo(401);
        assertThat(oldSession.call("/api/users/_health").statusCode()).as("the old session is over").isEqualTo(401);
        HttpResponse<String> again = visitor.postJson("/api/auth/password/reset",
                "{\"token\":\"" + token + "\",\"newPassword\":\"" + NEW + "\"}");
        assertThat(again.statusCode()).as("the link works once").isEqualTo(410);
    }

    @Test
    void aWeakPasswordIsRefusedAndTheLinkStaysUsable() throws Exception {
        String email = patientEmail();
        new Browser().postJson("/api/auth/password/forgot", "{\"email\":\"" + email + "\"}");
        String token = tokenIn(email);
        Browser visitor = new Browser();

        HttpResponse<String> weak = visitor.postJson("/api/auth/password/reset",
                "{\"token\":\"" + token + "\",\"newPassword\":\"abc\"}");

        assertThat(weak.statusCode()).as(weak.body()).isEqualTo(400);
        assertThat(new Browser().apiLogin(email, OLD).statusCode()).as("the old password still works").isEqualTo(200);
        assertThat(visitor.postJson("/api/auth/password/reset",
                "{\"token\":\"" + token + "\",\"newPassword\":\"" + NEW + "\"}").statusCode()).isEqualTo(204);
    }

    @Test
    void anUnknownTokenIsRefusedAndAnUnverifiedAccountGetsNoMail() throws Exception {
        String unverified = uniqueEmail("unverified");
        UUID id = console.createUser(unverified, OLD, false);
        insertPatient(id, unverified);

        new Browser().postJson("/api/auth/password/forgot", "{\"email\":\"" + unverified + "\"}");

        assertThat(mailbox.to(unverified, SUBJECT)).isEmpty();
        assertThat(new Browser().postJson("/api/auth/password/reset",
                "{\"token\":\"not-a-real-token\",\"newPassword\":\"" + NEW + "\"}").statusCode()).isEqualTo(400);
    }

    private String patientEmail() {
        String email = uniqueEmail("forgot");
        UUID id = console.createUser(email, OLD);
        insertPatient(id, email);
        return email;
    }

    private void insertPatient(UUID id, String email) {
        jdbc.update("insert into users (id, username, email) values (?, ?, ?) on conflict (id) do nothing", id, email, email);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Ben Salah', ?)",
                id, Date.valueOf("1990-04-12"));
    }

    private String tokenIn(String email) {
        List<Map<?, ?>> mails = mailbox.to(email, SUBJECT);
        assertThat(mails).as("the reset mail").isNotEmpty();
        Matcher link = LINK.matcher(mailbox.text(mails.get(0)));
        assertThat(link.find()).as("the link in the mail").isTrue();
        return link.group(1);
    }
}
