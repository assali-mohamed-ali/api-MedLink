package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MLink-55: {@code POST /api/auth/login} against the real Keycloak (endpoint 5 in plan.md).
 * The browser sends an email and a password to our API; the API asks Keycloak (password grant)
 * and opens a session. No Keycloak page, link or token ever reaches the browser.
 */
class LoginFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Login-Pass-2026!";

    final KeycloakConsole console = new KeycloakConsole();

    @Test
    void aVerifiedPatientLogsInAndGetsASession() throws Exception {
        String email = uniqueEmail("login");
        UUID id = console.createUser(email, PASSWORD);
        Browser browser = new Browser();

        HttpResponse<String> response = browser.apiLogin(email, PASSWORD);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"user\"", "\"id\":\"" + id + "\"", "\"email\":\"" + email + "\"")
                .as("only our three roles, not Keycloak's built-in ones")
                .contains("\"roles\":[\"PATIENT\"]");
        assertThat(browser.call("/api/users/_health").statusCode()).as("the session works").isEqualTo(200);
    }

    @Test
    void noTokenReachesTheBrowser() throws Exception {
        String email = uniqueEmail("login-token");
        console.createUser(email, PASSWORD);

        HttpResponse<String> response = new Browser().apiLogin(email, PASSWORD);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("access_token", "refresh_token", "id_token", "eyJ");
        assertThat(response.headers().allValues("Set-Cookie")).noneMatch(c -> c.contains("eyJ"));
    }

    @Test
    void theSessionIdChangesAtLogin() throws Exception {
        String email = uniqueEmail("login-fixation");
        console.createUser(email, PASSWORD);
        Browser browser = new Browser();
        browser.apiLogin(email, PASSWORD);
        String first = browser.cookies.value("JSESSIONID");

        browser.apiLogin(email, PASSWORD);

        assertThat(first).isNotNull();
        assertThat(browser.cookies.value("JSESSIONID")).isNotEqualTo(first);
    }

    @Test
    void aWrongPasswordAndAnUnknownEmailGetTheSameAnswer() throws Exception {
        String email = uniqueEmail("login-wrong");
        console.createUser(email, PASSWORD);
        Browser browser = new Browser();

        HttpResponse<String> wrongPassword = browser.apiLogin(email, "Wrong-Pass-2026!");
        HttpResponse<String> unknownEmail = browser.apiLogin(uniqueEmail("nobody"), PASSWORD);

        assertThat(wrongPassword.statusCode()).isEqualTo(401);
        assertThat(wrongPassword.body())
                .contains("\"code\":\"INVALID_CREDENTIALS\"", "E-mail ou mot de passe incorrect.");
        assertThat(unknownEmail.statusCode()).isEqualTo(401);
        assertThat(withoutTimestamp(unknownEmail.body())).isEqualTo(withoutTimestamp(wrongPassword.body()));
        assertThat(browser.call("/api/users/_health").statusCode()).as("no session").isEqualTo(401);
    }

    @Test
    void anUnverifiedEmailIsRefusedAndLeavesNoSessionAnywhere() throws Exception {
        String email = uniqueEmail("login-unverified");
        UUID id = console.createUser(email, PASSWORD, false);
        Browser browser = new Browser();

        HttpResponse<String> response = browser.apiLogin(email, PASSWORD);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"EMAIL_NOT_VERIFIED\"");
        assertThat(browser.call("/api/users/_health").statusCode()).as("no API session").isEqualTo(401);
        assertThat(console.sessions(id)).as("Keycloak accepted the password: its session is ended again").isEmpty();
    }

    @Test
    void aDisabledAccountIsRefused() throws Exception {
        String email = uniqueEmail("login-disabled");
        UUID id = console.createUser(email, PASSWORD);
        console.disable(id);
        Browser browser = new Browser();

        HttpResponse<String> response = browser.apiLogin(email, PASSWORD);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"ACCOUNT_DISABLED\"");
        assertThat(browser.call("/api/users/_health").statusCode()).as("no session").isEqualTo(401);
    }

    @Test
    void theLoginNeedsTheCsrfHeader() throws Exception {
        String email = uniqueEmail("login-csrf");
        console.createUser(email, PASSWORD);
        Browser browser = new Browser();
        browser.call("/api/auth/csrf");

        HttpResponse<String> response = browser.post(
                "/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", false);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(browser.call("/api/users/_health").statusCode()).as("no session").isEqualTo(401);
    }

    private static String withoutTimestamp(String body) {
        return body.replaceAll("\"timestamp\":\"[^\"]*\"", "");
    }
}
