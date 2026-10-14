package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * GitHub issue #28: an open session stops working once Keycloak no longer accepts the account.
 * The functional tests renew the access token on every call (see FunctionalTestBase), so the
 * check happens at the next call instead of within 5 minutes.
 */
class SessionFollowsAccountFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Session-Pass-2026!";

    final KeycloakConsole console = new KeycloakConsole();

    @Test
    void anActiveAccountKeepsItsSessionAcrossTokenRenewals() throws Exception {
        String email = uniqueEmail("active");
        console.createUser(email, PASSWORD);
        Browser browser = new Browser().login(email, PASSWORD);

        assertThat(browser.call("/api/users/_health").statusCode()).isEqualTo(200);
        assertThat(browser.call("/api/users/_health").statusCode()).isEqualTo(200);
    }

    @Test
    void anAccountDisabledInKeycloakLosesItsOpenSession() throws Exception {
        String email = uniqueEmail("disabled");
        UUID id = console.createUser(email, PASSWORD);
        Browser browser = new Browser().login(email, PASSWORD);
        assertThat(browser.call("/api/users/_health").statusCode()).isEqualTo(200);

        console.disable(id);

        HttpResponse<String> refused = browser.call("/api/users/_health");
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(refused.body()).contains("\"code\":\"SESSION_EXPIRED\"");
        assertThat(browser.call("/api/users/_health").statusCode()).as("the session is gone").isEqualTo(401);
    }

    @Test
    void signingTheUserOutInKeycloakEndsTheApiSessionToo() throws Exception {
        String email = uniqueEmail("signedout");
        UUID id = console.createUser(email, PASSWORD);
        Browser browser = new Browser().login(email, PASSWORD);
        assertThat(browser.call("/api/users/_health").statusCode()).isEqualTo(200);

        console.signOutEverywhere(id);

        assertThat(browser.call("/api/users/_health").statusCode()).isEqualTo(401);
    }
}
