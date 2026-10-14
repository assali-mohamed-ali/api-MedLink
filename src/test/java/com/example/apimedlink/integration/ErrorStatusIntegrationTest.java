package com.example.apimedlink.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Through a real server, not MockMvc: an error sent with sendError (CSRF refused, wrong media type)
 * is forwarded to /error. That forward must keep its status instead of turning into a 401.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ErrorStatusIntegrationTest {

    @LocalServerPort
    int port;

    private int post(String path, String contentType, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void aWriteWithoutTheCsrfHeaderIs403Not401() throws Exception {
        assertThat(post("/api/auth/verify-email", "application/json", "{\"token\":\"x\"}")).isEqualTo(403);
    }

    @Test
    void aProtectedRouteWithoutASessionIsStill401() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/users"))
                .GET()
                .build();
        assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(401);
    }
}
