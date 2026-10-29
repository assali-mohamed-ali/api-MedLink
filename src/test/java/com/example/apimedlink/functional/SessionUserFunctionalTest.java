package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * MLink-53: {@code GET /api/auth/me} (endpoint 6 in plan.md). React calls it when the page loads, to know
 * who is logged in after a refresh: the user object of the login, from the session, or 401.
 */
class SessionUserFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Session-User-2026!";

    final KeycloakConsole console = new KeycloakConsole();

    @Test
    void aLoggedInPatientGetsTheSameUserAsTheLogin() throws Exception {
        String email = uniqueEmail("me-patient");
        console.createUser(email, PASSWORD);
        Browser browser = new Browser();
        Map<?, ?> loginUser = (Map<?, ?>) Browser.json(browser.apiLogin(email, PASSWORD)).get("user");

        HttpResponse<String> me = browser.call("/api/auth/me");

        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(Browser.json(me).get("user")).as("the login's user object").isEqualTo(loginUser);
        assertThat(loginUser.get("roles")).isEqualTo(List.of("PATIENT"));
    }

    @Test
    void withoutASessionItIs401() throws Exception {
        HttpResponse<String> me = new Browser().call("/api/auth/me");

        assertThat(me.statusCode()).isEqualTo(401);
        assertThat(Browser.json(me).get("code")).as("React logs the user out on this code").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void afterLogoutItIs401() throws Exception {
        String email = uniqueEmail("me-logout");
        console.createUser(email, PASSWORD);
        Browser browser = new Browser();
        browser.apiLogin(email, PASSWORD);
        browser.logout();

        assertThat(browser.call("/api/auth/me").statusCode()).isEqualTo(401);
    }

    @Test
    void thePendingCodeStepIsNotASession() throws Exception {
        String email = uniqueEmail("me-pending");
        console.createUser(email, PASSWORD, "/practitioners");
        Browser browser = new Browser();
        assertThat(Browser.json(browser.apiLogin(email, PASSWORD)).get("mfaRequired")).isEqualTo(true);

        assertThat(browser.call("/api/auth/me").statusCode()).as("password alone, no code yet").isEqualTo(401);
    }

    @Test
    void aPractitionerAfterTheCodeStepGetsTheirRole() throws Exception {
        String email = uniqueEmail("me-practitioner");
        console.createUser(email, PASSWORD, "/practitioners");
        Browser browser = new Browser().login(email, PASSWORD);

        HttpResponse<String> me = browser.call("/api/auth/me");

        assertThat(me.statusCode()).isEqualTo(200);
        Map<?, ?> user = (Map<?, ?>) Browser.json(me).get("user");
        assertThat(user.get("email")).isEqualTo(email);
        assertThat(user.get("firstName")).isEqualTo("Console");
        assertThat(user.get("lastName")).isEqualTo("User");
        assertThat(user.get("roles")).isEqualTo(List.of("PRACTITIONER"));
        assertThat(user.get("roles")).isEqualTo(browser.sessionRoles());
    }
}
