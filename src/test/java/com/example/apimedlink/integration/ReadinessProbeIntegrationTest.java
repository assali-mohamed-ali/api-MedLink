package com.example.apimedlink.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * GitHub #49 (MLink-122), review on #94: /readyz is what Docker, Terraform and the deployment check call, so it
 * must say DOWN when the database is gone (requests would fail), while /livez stays UP (the process is fine:
 * restarting it would not bring the database back). The real prod profile; this class stops its own Postgres
 * container, so its context is never reused. Short pool timeouts so the outage is seen in seconds.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "keycloak.client-secret=unused",
            "keycloak.admin.client-secret=unused",
            "management.server.port=0",
            "spring.datasource.hikari.connection-timeout=2000",
            "spring.datasource.hikari.validation-timeout=1000"
        })
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("prod")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReadinessProbeIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    PostgreSQLContainer postgres;

    @Test
    void readyzGoesDownWhenTheDatabaseIsGoneAndLivezStaysUp() throws Exception {
        assertThat(get("/readyz").statusCode()).as("ready while the database is up").isEqualTo(200);

        postgres.stop();

        HttpResponse<String> readyz = get("/readyz");
        assertThat(readyz.statusCode()).as("not ready without its database").isEqualTo(503);
        assertThat(readyz.body()).contains("\"status\":\"DOWN\"").doesNotContain("components");

        HttpResponse<String> livez = get("/livez");
        assertThat(livez.statusCode()).as("still alive: a restart would not help").isEqualTo(200);
        assertThat(livez.body()).contains("\"status\":\"UP\"");
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
