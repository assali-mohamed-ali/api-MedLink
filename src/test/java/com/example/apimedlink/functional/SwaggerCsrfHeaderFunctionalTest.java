package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Swagger "Try it out" cannot guess the CSRF handshake: every write needs the
 * {@code X-XSRF-TOKEN} header or Spring answers 403. {@code OpenApiConfig} declares
 * that header on every write operation, so the Swagger form shows a field for it.
 */
class SwaggerCsrfHeaderFunctionalTest extends FunctionalTestBase {

    static final String CSRF_HEADER = "X-XSRF-TOKEN";

    final ObjectMapper json = new ObjectMapper();

    @Test
    void writeOperationsDeclareTheCsrfHeader() throws Exception {
        Map<String, Object> api = apiDocs();

        for (String path : List.of(
                "/api/auth/register", "/api/auth/login", "/api/auth/verify-email", "/api/auth/verify-email/resend")) {
            assertThat(parameters(api, path, "post"))
                    .as(path + " declares the CSRF header for Swagger")
                    .anyMatch(p -> CSRF_HEADER.equals(p.get("name"))
                            && "header".equals(p.get("in"))
                            && Boolean.TRUE.equals(p.get("required")));
        }
    }

    @Test
    void readOperationsDeclareNoCsrfHeader() throws Exception {
        Map<String, Object> api = apiDocs();

        assertThat(parameters(api, "/api/auth/csrf", "get"))
                .as("GET /api/auth/csrf stays a plain read")
                .noneMatch(p -> CSRF_HEADER.equals(p.get("name")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> apiDocs() throws Exception {
        HttpResponse<String> response = new Browser().call("/v3/api-docs");

        assertThat(response.statusCode()).isEqualTo(200);
        return json.readValue(response.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parameters(Map<String, Object> api, String path, String method) {
        Map<String, Object> paths = (Map<String, Object>) api.get("paths");
        assertThat(paths).as("documented paths").containsKey(path);
        Map<String, Object> item = (Map<String, Object>) paths.get(path);
        assertThat(item).as(path + " documents " + method).containsKey(method);
        Map<String, Object> operation = (Map<String, Object>) item.get(method);
        Object parameters = operation.get("parameters");
        assertThat(parameters).as(path + " " + method + " has parameters").isInstanceOf(List.class);
        return (List<Map<String, Object>>) parameters;
    }
}
