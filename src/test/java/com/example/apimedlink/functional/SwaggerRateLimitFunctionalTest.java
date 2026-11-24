package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The per-IP limits answer 429 from a filter (GitHub #51), which springdoc cannot see. The API contract
 * must still say so, on exactly the limited endpoints, so a client knows to read {@code Retry-After}.
 */
class SwaggerRateLimitFunctionalTest extends FunctionalTestBase {

    final ObjectMapper json = new ObjectMapper();

    @Test
    void limitedEndpointsDocumentTheirTooManyRequestsAnswer() throws Exception {
        Map<String, Object> api = apiDocs();

        for (String path : List.of("/api/auth/register", "/api/auth/login", "/api/auth/verify-email/resend")) {
            Map<String, Object> tooMany = responses(api, path).get("429");
            assertThat(tooMany).as(path + " documents 429").isNotNull();
            assertThat(map(tooMany, "headers")).as(path + " 429 headers").containsKey("Retry-After");
            Map<String, Object> body = map(map(tooMany, "content"), "application/json");
            assertThat(map(map(body, "schema"), "properties"))
                    .as(path + " 429 body is the API's error shape")
                    .containsKeys("timestamp", "status", "error", "code", "message", "details");
            assertThat(map(body, "example")).containsEntry("code", "TOO_MANY_REQUESTS").containsEntry("status", 429);
        }
    }

    @Test
    void endpointsWithoutALimitDocumentNoTooManyRequests() throws Exception {
        assertThat(responses(apiDocs(), "/api/auth/verify-email")).doesNotContainKey("429");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> apiDocs() throws Exception {
        HttpResponse<String> response = new Browser().call("/v3/api-docs");

        assertThat(response.statusCode()).isEqualTo(200);
        return json.readValue(response.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> responses(Map<String, Object> api, String path) {
        Map<String, Object> post = map(map(map(api, "paths"), path), "post");
        return (Map<String, Map<String, Object>>) post.get("responses");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        assertThat(parent).as("has " + key).containsKey(key);
        return (Map<String, Object>) parent.get(key);
    }
}
