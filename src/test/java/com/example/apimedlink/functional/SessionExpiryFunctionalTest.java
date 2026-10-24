package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.apache.catalina.Context;
import org.apache.catalina.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

/**
 * MLink-86 (story MLink-22): a session opened by the login lasts 15 idle minutes, and an unknown,
 * ended or expired session is refused with our JSON error, never a redirect. No real 15-minute wait
 * (Tomcat rounds timeouts to whole minutes and sweeps once a minute): the test reads the real
 * session the login opened from Tomcat, and expires it the way Tomcat does after 15 idle minutes.
 */
class SessionExpiryFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Expiry-Pass-2026!";

    final KeycloakConsole console = new KeycloakConsole();

    @Autowired
    WebServerApplicationContext server;

    @Test
    void theSessionOpenedByTheLoginEndsAfterFifteenIdleMinutes() throws Exception {
        String email = uniqueEmail("expiry");
        console.createUser(email, PASSWORD);
        Browser browser = new Browser();
        browser.apiLogin(email, PASSWORD);

        Session session = tomcatSession(browser.cookies.value("JSESSIONID"));

        assertThat(session).as("the login opened a server session").isNotNull();
        assertThat(session.getMaxInactiveInterval()).as("15 minutes, in seconds").isEqualTo(900);
    }

    @Test
    void aSessionTomcatExpiresIsRefusedWithOurJsonError() throws Exception {
        String email = uniqueEmail("expiry-idle");
        console.createUser(email, PASSWORD);
        Browser browser = new Browser();
        browser.apiLogin(email, PASSWORD);
        assertThat(browser.call("/api/users/_health").statusCode()).as("logged in").isEqualTo(200);

        // What Tomcat does by itself after 15 idle minutes, without waiting for them
        tomcatSession(browser.cookies.value("JSESSIONID")).expire();

        assertRefusedAsLoggedOut(browser.call("/api/users/_health"));
    }

    @Test
    void anUnknownSessionIsRefusedWithOurJsonErrorNotARedirect() throws Exception {
        Browser browser = new Browser();
        browser.cookies.set("JSESSIONID", "0123456789ABCDEF0123456789ABCDEF");

        assertRefusedAsLoggedOut(browser.call("/api/users/_health"));
    }

    @Test
    void aSessionEndedByLogoutIsRefusedWithOurJsonError() throws Exception {
        String email = uniqueEmail("expiry-old");
        console.createUser(email, PASSWORD);
        Browser browser = new Browser();
        browser.apiLogin(email, PASSWORD);
        String oldSession = browser.cookies.value("JSESSIONID");
        browser.logout();

        Browser stale = new Browser();
        stale.cookies.set("JSESSIONID", oldSession);

        assertRefusedAsLoggedOut(stale.call("/api/users/_health"));
    }

    private static void assertRefusedAsLoggedOut(HttpResponse<String> response) {
        assertThat(response.previousResponse()).as("never a redirect").isEmpty();
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
        assertThat(response.body()).contains("\"status\":401", "\"code\":\"UNAUTHENTICATED\"");
    }

    /** The session Tomcat really keeps for this id (null when there is none). */
    private Session tomcatSession(String id) throws Exception {
        Context app = (Context) ((TomcatWebServer) server.getWebServer()).getTomcat().getHost().findChildren()[0];
        return app.getManager().findSession(id);
    }
}
