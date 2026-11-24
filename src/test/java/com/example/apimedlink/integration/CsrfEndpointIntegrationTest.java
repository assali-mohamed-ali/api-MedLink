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
import org.springframework.http.HttpHeaders;

/**
 * Endpoint 1 (plan.md §4), MLink-53: React gets its CSRF token before the first write.
 *
 * <p>Through a real server, not MockMvc: Spring Security's {@code csrf()} test helper swaps the
 * CSRF repository of the shared filter for a session-based one, so in a MockMvc context another
 * test class had used, the XSRF-TOKEN cookie was never written (red on CI, which runs the
 * classes in another order than Windows).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class CsrfEndpointIntegrationTest {

    static final String REACT = "http://localhost:3000";

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder preflight(String requestHeaders) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/register"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header(HttpHeaders.ORIGIN, REACT)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, requestHeaders);
    }

    @Test
    void withoutASessionItAnswers204AndSetsAReadableCsrfCookie() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/csrf")).GET());

        assertThat(response.statusCode()).isEqualTo(204);
        List<String> cookies = response.headers().allValues(HttpHeaders.SET_COOKIE);
        assertThat(cookies).filteredOn(c -> c.startsWith("XSRF-TOKEN="))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c).doesNotStartWith("XSRF-TOKEN=;");          // a real value
                    assertThat(c).doesNotContainIgnoringCase("HttpOnly");    // React must read it
                });
        assertThat(cookies).as("no session for a visitor").noneMatch(c -> c.startsWith("JSESSIONID="));
    }

    @Test
    void reactOnAnotherOriginMaySendTheCsrfHeader() throws Exception {
        HttpResponse<String> response = send(preflight("content-type,x-xsrf-token"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).hasValue(REACT);
        assertThat(response.headers().firstValue(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).hasValue("true");
        assertThat(response.headers().firstValue(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS).orElse("").toLowerCase())
                .contains("x-xsrf-token");
    }

    @Test
    void aHeaderWeDoNotAllowIsRefused() throws Exception {
        assertThat(send(preflight("x-not-allowed")).statusCode()).isEqualTo(403);
    }
}
