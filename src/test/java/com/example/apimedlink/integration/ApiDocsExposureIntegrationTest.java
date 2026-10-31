package com.example.apimedlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.example.apimedlink.TestcontainersConfiguration;
import java.io.IOException;
import java.io.UncheckedIOException;
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
 * GitHub #65 (MLink-127): staging and prod serve neither the API reference UI (Scalar since #100) nor the OpenAPI
 * description, so an anonymous visitor learns no route, schema or security assumption of a health-data API. Dev
 * keeps both for the developers and for DAST (ZAP runs the dev profile). The old Swagger UI paths stay in the list:
 * they must never come back either. The real profile files, on real ports; the Keycloak secrets have no default
 * outside dev: dummy ones.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"keycloak.client-secret=unused", "keycloak.admin.client-secret=unused"})
@Import(TestcontainersConfiguration.class)
class ApiDocsExposureIntegrationTest {

    static final List<String> DOC_PATHS = List.of(
            "/v3/api-docs",
            "/v3/api-docs/swagger-config",
            "/scalar",
            "/scalar/scalar.js",
            "/swagger-ui.html",
            "/swagger-ui/index.html");

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
            // every path is checked, so a failure lists all the exposed ones
            assertSoftly(softly -> {
                for (String path : DOC_PATHS) {
                    HttpResponse<String> response = get(port, path);

                    softly.assertThat(response.statusCode()).as(path).isIn(401, 404);
                    // what only a real description or the UI holds (a 404 body echoes the path itself)
                    softly.assertThat(response.body())
                            .as(path)
                            .doesNotContain("\"openapi\"", "\"paths\"", "Scalar API Reference", "swagger-ui-bundle");
                }
            });
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
        void theScalarReferenceStays() throws Exception {
            HttpResponse<String> response = get(port, "/scalar");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("Scalar API Reference", "/scalar/scalar.js");
        }
    }

    static HttpResponse<String> get(int port, String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        try {
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
