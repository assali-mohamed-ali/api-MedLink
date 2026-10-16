package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MLink-54: {@code POST /api/auth/logout} against the real Keycloak (endpoint 7 in plan.md).
 * After logout the user is really out: no API session and no Keycloak session.
 */
class LogoutFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Logout-Pass-2026!";

    final KeycloakConsole console = new KeycloakConsole();

    @Test
    void afterLogoutTheSessionNoLongerWorks() throws Exception {
        String email = uniqueEmail("logout");
        console.createUser(email, PASSWORD);
        Browser browser = new Browser();
        browser.apiLogin(email, PASSWORD);
        assertThat(browser.call("/api/users/_health").statusCode()).as("logged in").isEqualTo(200);

        HttpResponse<String> response = browser.logout();

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(browser.call("/api/users/_health").statusCode()).as("logged out").isEqualTo(401);
    }

    @Test
    void logoutEndsTheKeycloakSessionToo() throws Exception {
        String email = uniqueEmail("logout-kc");
        UUID id = console.createUser(email, PASSWORD);
        Browser browser = new Browser();
        browser.apiLogin(email, PASSWORD);
        assertThat(console.sessions(id)).as("the login opened a Keycloak session").isNotEmpty();

        browser.logout();

        assertThat(console.sessions(id)).as("no Keycloak session left").isEmpty();
    }

    @Test
    void logoutExpiresTheSessionCookie() throws Exception {
        String email = uniqueEmail("logout-cookie");
        console.createUser(email, PASSWORD);
        Browser browser = new Browser();
        browser.apiLogin(email, PASSWORD);

        HttpResponse<String> response = browser.logout();

        assertThat(response.headers().allValues("Set-Cookie"))
                .as("the browser is told to drop the session cookie")
                .anySatisfy(cookie -> assertThat(cookie).startsWith("JSESSIONID=").containsIgnoringCase("Max-Age=0"));
    }

    @Test
    void logoutNeedsTheCsrfHeader() throws Exception {
        String email = uniqueEmail("logout-csrf");
        console.createUser(email, PASSWORD);
        Browser browser = new Browser();
        browser.apiLogin(email, PASSWORD);

        HttpResponse<String> response = browser.post("/api/auth/logout", "", false);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(browser.call("/api/users/_health").statusCode())
                .as("a forged logout from another site does nothing").isEqualTo(200);
    }

    @Test
    void logoutWithoutASessionIs401() throws Exception {
        Browser browser = new Browser();
        browser.call("/api/auth/csrf");

        assertThat(browser.logout().statusCode()).isEqualTo(401);
    }
}