package com.example.apimedlink.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** The cookies the browser gets: what the DAST (ZAP) gate checks. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class SecurityCookiesIntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void sessionAndCsrfCookiesAreSameSiteAndOnlyTheCsrfOneIsReadable() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/"))
                .GET()
                .build();
        HttpResponse<Void> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
        List<String> cookies = response.headers().allValues("Set-Cookie");

        assertThat(cookies).anySatisfy(c -> assertThat(c).startsWith("JSESSIONID="));
        assertThat(cookies).anySatisfy(c -> assertThat(c).startsWith("XSRF-TOKEN="));
        assertThat(cookies).allSatisfy(c -> assertThat(c).containsIgnoringCase("SameSite=Lax"));
        assertThat(cookies)
                .filteredOn(c -> c.startsWith("JSESSIONID="))
                .allSatisfy(c -> assertThat(c).containsIgnoringCase("HttpOnly"));
        // The SPA reads XSRF-TOKEN and echoes it in X-XSRF-TOKEN
        assertThat(cookies)
                .filteredOn(c -> c.startsWith("XSRF-TOKEN="))
                .allSatisfy(c -> assertThat(c).doesNotContainIgnoringCase("HttpOnly"));
    }
}
