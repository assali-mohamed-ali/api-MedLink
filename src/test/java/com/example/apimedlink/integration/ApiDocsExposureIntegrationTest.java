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
import org.springframework.test.context.TestPropertySource;

/**
 * GitHub #65 (MLink-127): staging and prod serve neither Swagger UI nor the OpenAPI description, so an
 * anonymous visitor learns no route, schema or security assumption of a health-data API, and the Swagger UI
 * bundle (DOMPurify inside) is never sent to a browser there. Dev keeps both for the developers and for DAST
 * (ZAP runs the dev profile). The real profile files, on real ports; the Keycloak secrets have no default
 * outside dev: dummy ones.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"keycloak.client-secret=unused", "keycloak.admin.client-secret=unused"})
@Import(TestcontainersConfiguration.class)
class ApiDocsExposureIntegrationTest {

    static final List<String> DOC_PATHS =
            List.of("/v3/api-docs", "/v3/api-docs/swagger-config", "/swagger-ui.html", "/swagger-ui/index.html");

    @Nested
    @ActiveProfiles("staging")
    class Staging extends NoApiDocs {}

    @Nested
    @ActiveProfiles("prod")
    class Prod extends NoApiDocs {}

    /** Staging and prod: the management port is a random one in tests, as in ManagementPortIntegrationTest. */
    @TestPropertySource(properties = "management.server.port=0")
    abstract static class NoApiDocs {

        @LocalServerPort
        int port;

        @Test
        void noDocumentationForAnAnonymousVisitor() throws Exception {
            for (String path : DOC_PATHS) {
                HttpResponse<String> response = get(port, path);

                assertThat(response.statusCode()).as(path).isIn(401, 404);
                assertThat(response.body()).as(path).doesNotContain("openapi", "swagger-ui", "/api/auth/");
            }
        }
    }

    @Nested
    @ActiveProfiles("dev")
    class Dev {

        @LocalServerPort
        int port;

        @Test
        void theOpenApiDescriptionStays() throws Exception {
            HttpResponse<String> response = get(port, "/v3/api-docs");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"openapi\"", "/api/auth/login");
        }

        @Test
        void swaggerUiStays() throws Exception {
            HttpResponse<String> response = get(port, "/swagger-ui/index.html");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("swagger-ui");
        }
    }

    static HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
