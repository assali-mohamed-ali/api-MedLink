package com.example.apimedlink.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * MLink-84: under the staging and prod profiles the session and XSRF-TOKEN cookies are {@code Secure}
 * (HTTPS only), even when the request reached the API over plain HTTP (TLS ends at the proxy).
 * The real profile files, on a real port; not a functional test, which runs one profile on one fixed
 * port for every class. The Keycloak secrets have no default outside dev: dummy ones, no login here.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"keycloak.client-secret=unused", "keycloak.admin.client-secret=unused"})
@Import(TestcontainersConfiguration.class)
class SecureCookiesIntegrationTest {

    @Nested
    @ActiveProfiles("staging")
    class Staging {

        @LocalServerPort
        int port;

        @Test
        void sessionAndCsrfCookiesAreSecure() throws Exception {
            assertSecureCookies(port);
        }
    }

    @Nested
    @ActiveProfiles("prod")
    class Prod {

        @LocalServerPort
        int port;

        @Test
        void sessionAndCsrfCookiesAreSecure() throws Exception {
            assertSecureCookies(port);
        }
    }

    static void assertSecureCookies(int port) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).GET().build();
        HttpResponse<Void> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
        List<String> cookies = response.headers().allValues("Set-Cookie");

        assertThat(cookies).anySatisfy(c -> assertThat(c).startsWith("JSESSIONID="));
        assertThat(cookies).anySatisfy(c -> assertThat(c).startsWith("XSRF-TOKEN="));
        assertThat(cookies).allSatisfy(c -> assertThat(c)
                .containsIgnoringCase("Secure")
                .containsIgnoringCase("SameSite=Strict"));
    }
}
