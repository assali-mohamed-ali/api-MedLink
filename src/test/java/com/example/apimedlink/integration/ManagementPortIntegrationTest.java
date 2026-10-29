package com.example.apimedlink.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * GitHub #49 (MLink-122): in staging and prod, Actuator (metrics included) is served on a separate management
 * port that only the private network reaches; the public port keeps only /livez and /readyz, which say UP or
 * DOWN and nothing else, for the deployment check and the container healthchecks. Dev keeps Actuator on the
 * main port. The real profile files, on real ports; the management port is a random one here (its value,
 * 9000, is checked by ManagementPortConfigTest). The Keycloak secrets have no default outside dev: dummy ones.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"keycloak.client-secret=unused", "keycloak.admin.client-secret=unused"})
@Import(TestcontainersConfiguration.class)
class ManagementPortIntegrationTest {

    @Nested
    @ActiveProfiles("staging")
    class Staging extends PrivateManagementPort {}

    @Nested
    @ActiveProfiles("prod")
    class Prod extends PrivateManagementPort {}

    /** Staging and prod: the management port is a random one in tests (only for them, not for dev). */
    @TestPropertySource(properties = "management.server.port=0")
    abstract static class PrivateManagementPort {

        @LocalServerPort
        int port;

        @LocalManagementPort
        int managementPort;

        @Test
        void prometheusIsNotOnTheMainPort() throws Exception {
            HttpResponse<String> response = get(port, "/actuator/prometheus");

            assertThat(response.statusCode()).as("anonymous, public port").isIn(401, 404);
            assertThat(response.body()).doesNotContain("jvm_memory");
        }

        @Test
        void actuatorHealthIsNotOnTheMainPort() throws Exception {
            HttpResponse<String> response = get(port, "/actuator/health");

            assertThat(response.statusCode()).as("anonymous, public port").isIn(401, 404);
            assertThat(response.body()).doesNotContain("\"status\":\"UP\"");
        }

        @Test
        void prometheusIsOnTheManagementPort() throws Exception {
            assertThat(managementPort).as("a port of its own").isNotEqualTo(port);
            HttpResponse<String> response = get(managementPort, "/actuator/prometheus");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("jvm_memory");
        }

        @Test
        void readyzAnswersOnTheMainPort() throws Exception {
            assertUp(get(port, "/readyz"));
        }

        @Test
        void livezAnswersOnTheMainPort() throws Exception {
            assertUp(get(port, "/livez"));
        }

        @Test
        void readyzDoesNotRevealDetails() throws Exception {
            assertThat(get(port, "/readyz").body()).doesNotContain("components", "details", "db");
        }
    }

    /** Dev (the setup scripts, DAST) keeps Actuator on the main port, and gains /readyz like every profile. */
    @Nested
    @ActiveProfiles("dev")
    class Dev {

        @LocalServerPort
        int port;

        @Test
        void prometheusStaysOnTheMainPort() throws Exception {
            HttpResponse<String> response = get(port, "/actuator/prometheus");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("jvm_memory");
        }

        @Test
        void actuatorHealthStaysOnTheMainPort() throws Exception {
            assertUp(get(port, "/actuator/health"));
        }

        @Test
        void readyzAnswersToo() throws Exception {
            assertUp(get(port, "/readyz"));
        }
    }

    static HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    static void assertUp(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }
}
