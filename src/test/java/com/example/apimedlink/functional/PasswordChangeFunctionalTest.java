package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.repository.SessionRevocationStore;
import com.example.apimedlink.service.KeycloakAdminService;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * MLink-91: {@code POST /api/auth/password} with a session (endpoint 10 in plan.md).
 */
class PasswordChangeFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Change-Pass-2026!";
    static final String NEXT_PASSWORD = "Changed-Pass-2026!";

    final KeycloakConsole console = new KeycloakConsole();

    @Autowired
    KeycloakAdminService keycloak;

    @Autowired
    SessionRevocationStore revocations;

    @Test
    void aPatientChangesTheirPassword() throws Exception {
        String email = uniqueEmail("change");
        console.createUser(email, PASSWORD);
        Browser browser = new Browser().login(email, PASSWORD);

        HttpResponse<String> response = browser.postJson("/api/auth/password",
                "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEXT_PASSWORD + "\"}");

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(new Browser().apiLogin(email, PASSWORD).statusCode()).isEqualTo(401);
        HttpResponse<String> after = new Browser().apiLogin(email, NEXT_PASSWORD);
        assertThat(after.statusCode()).isEqualTo(200);
        assertThat(after.body()).contains("\"user\"").doesNotContain("passwordChangeRequired");
    }

    @Test
    void aChangeEndsEverySessionOfTheUser() throws Exception {
        String email = uniqueEmail("change-sessions");
        UUID id = console.createUser(email, PASSWORD);
        Browser here = new Browser().login(email, PASSWORD);
        Browser elsewhere = new Browser().login(email, PASSWORD);

        HttpResponse<String> response = here.postJson("/api/auth/password",
                "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEXT_PASSWORD + "\"}");

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(here.call("/api/users/_health").statusCode()).as("this session, at once").isEqualTo(401);
        assertThat(elsewhere.call("/api/users/_health").statusCode()).as("another browser").isEqualTo(401);
        assertThat(console.sessions(id)).as("Keycloak sessions").isEmpty();
        assertThat(revocations.revokedAt(id)).as("the API's own mark").isPresent();
    }

    /** What a failed Keycloak sign-out leaves behind after a change: only the API's own mark stands. */
    @Test
    void anOldSessionIsRefusedEvenWhileKeycloakStillAcceptsIt() throws Exception {
        String email = uniqueEmail("change-mark");
        UUID id = console.createUser(email, PASSWORD);
        Browser old = new Browser().login(email, PASSWORD);
        assertThat(old.call("/api/users/_health").statusCode()).isEqualTo(200);

        revocations.revoke(id);

        assertThat(console.sessions(id)).as("Keycloak still has the session").isNotEmpty();
        assertThat(old.call("/api/users/_health").statusCode()).as("the API refuses it").isEqualTo(401);
        assertThat(new Browser().login(email, PASSWORD).call("/api/users/_health").statusCode())
                .as("a session opened after the mark").isEqualTo(200);
    }

    @Test
    void aWrongCurrentPasswordIsRefusedWithoutChangingThePassword() throws Exception {
        String email = uniqueEmail("change-wrong");
        console.createUser(email, PASSWORD);
        Browser browser = new Browser().login(email, PASSWORD);

        HttpResponse<String> response = browser.postJson("/api/auth/password",
                "{\"currentPassword\":\"Wrong-Pass-2026!\",\"newPassword\":\"" + NEXT_PASSWORD + "\"}");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"INVALID_CURRENT_PASSWORD\"");
        assertThat(new Browser().apiLogin(email, PASSWORD).statusCode()).isEqualTo(200);
    }

    @Test
    void aWeakPasswordKeepsTheFrenchPolicyMessage() throws Exception {
        String email = uniqueEmail("change-weak");
        console.createUser(email, PASSWORD);
        Browser browser = new Browser().login(email, PASSWORD);

        HttpResponse<String> response = browser.postJson("/api/auth/password",
                "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"Aa1!short\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"PASSWORD_POLICY\"",
                "Le mot de passe doit contenir au moins 12 caractères.");
    }

    @Test
    void aReusedPasswordIsRefused() throws Exception {
        String email = uniqueEmail("change-reuse");
        UUID id = keycloak.createUser(email, email, "Reuse", "User", "/patients");
        keycloak.setEmailVerified(id, true);
        keycloak.setPassword(id, PASSWORD);
        keycloak.setPassword(id, NEXT_PASSWORD);
        Browser browser = new Browser().login(email, NEXT_PASSWORD);

        HttpResponse<String> response = browser.postJson("/api/auth/password",
                "{\"currentPassword\":\"" + NEXT_PASSWORD + "\",\"newPassword\":\"" + PASSWORD + "\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"PASSWORD_POLICY\"",
                "Le mot de passe doit être différent de vos 3 derniers mots de passe.");
    }

    @Test
    void changingWithoutASessionOrAPendingChangeIsRefused() throws Exception {
        HttpResponse<String> response = new Browser().postJson("/api/auth/password",
                "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEXT_PASSWORD + "\"}");

        assertThat(response.statusCode()).isEqualTo(401);
    }
}
