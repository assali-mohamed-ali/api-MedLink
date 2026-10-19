package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * MLink-84 (story MLink-22): the cookies a browser gets around a login. The session cookie is never
 * readable by scripts and never sent from another site ({@code SameSite=Strict}: no Keycloak redirect
 * comes back any more, decision #58); the {@code XSRF-TOKEN} cookie stays readable by React. Plain
 * HTTP here, as in dev: {@code Secure} under the staging and prod profiles is checked by
 * {@code SecureCookiesIntegrationTest}.
 */
class SessionCookieFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Cookie-Pass-2026!";

    final KeycloakConsole console = new KeycloakConsole();

    @Test
    void theSessionCookieOfALoginIsHttpOnlyAndSameSiteStrict() throws Exception {
        String email = uniqueEmail("cookie");
        console.createUser(email, PASSWORD);

        HttpResponse<String> response = new Browser().apiLogin(email, PASSWORD);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(cookie(response, "JSESSIONID"))
                .containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("SameSite=Strict")
                .containsIgnoringCase("Path=/");
    }

    @Test
    void theCsrfCookieIsSameSiteStrictAndReadableByReact() throws Exception {
        HttpResponse<String> response = new Browser().call("/api/auth/csrf");

        assertThat(cookie(response, "XSRF-TOKEN"))
                .containsIgnoringCase("SameSite=Strict")
                .as("React reads it and echoes it in X-XSRF-TOKEN")
                .doesNotContainIgnoringCase("HttpOnly");
    }

    @Test
    void aSessionOpenedBeforeTheLoginIsNotTheOneTheLoginUses() throws Exception {
        String email = uniqueEmail("cookie-fixation");
        console.createUser(email, PASSWORD);
        Browser browser = new Browser();
        browser.call("/");
        String beforeLogin = browser.cookies.value("JSESSIONID");
        assertThat(beforeLogin).as("an anonymous session, the id an attacker could plant").isNotNull();

        browser.apiLogin(email, PASSWORD);

        assertThat(browser.cookies.value("JSESSIONID")).isNotEqualTo(beforeLogin);
        assertThat(browser.call("/api/users/_health").statusCode()).as("the new session works").isEqualTo(200);
        Browser attacker = new Browser();
        attacker.cookies.set("JSESSIONID", beforeLogin);
        assertThat(attacker.call("/api/users/_health").statusCode())
                .as("the id from before the login opens nothing")
                .isEqualTo(401);
    }

    /** The one Set-Cookie of this response for that cookie name. */
    private static String cookie(HttpResponse<String> response, String name) {
        List<String> cookies = response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith(name + "="))
                .toList();
        assertThat(cookies).as("one Set-Cookie for " + name).hasSize(1);
        return cookies.get(0);
    }
}
