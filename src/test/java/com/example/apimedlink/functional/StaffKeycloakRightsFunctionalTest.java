package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * GitHub #80 (threat model E3, MLink-121): staff act only through the API. Their Keycloak account must not
 * read or manage users in Keycloak itself (admin console or admin REST API), where the API's rules and audit
 * trail do not apply. The API's own admin calls use the service account medlink-admin, which keeps its rights.
 */
class StaffKeycloakRightsFunctionalTest extends FunctionalTestBase {

    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"access_token\":\"([^\"]+)\"");

    @Test
    void staffCannotListUsersInKeycloak() throws Exception {
        String staff = staffToken();

        assertThat(adminApi("GET", "/users", staff).statusCode()).as("Keycloak admin API, users, as staff").isEqualTo(403);
    }

    @Test
    void staffCannotCreateAUserInKeycloak() throws Exception {
        String staff = staffToken();
        String user = "{\"username\":\"" + uniqueEmail("by-staff") + "\",\"enabled\":true}";

        assertThat(adminApi("POST", "/users", staff, user).statusCode()).as("create a user, as staff").isEqualTo(403);
    }

    @Test
    void staffCannotReadTheRealmSettings() throws Exception {
        String staff = staffToken();

        assertThat(adminApi("GET", "", staff).statusCode()).as("realm settings, as staff").isEqualTo(403);
    }

    @Test
    void theApisServiceAccountKeepsItsRights() throws Exception {
        String serviceAccount = token("grant_type=client_credentials&client_id=medlink-admin&client_secret="
                + enc(ADMIN_CLIENT_SECRET));

        assertThat(adminApi("GET", "/users?max=1", serviceAccount).statusCode())
                .as("the API's admin calls still work").isEqualTo(200);
    }

    /**
     * What a staff member can do with their own password and no secret: Keycloak's public client admin-cli
     * (it exists in every realm and accepts the password grant).
     */
    private static String staffToken() throws Exception {
        return token("grant_type=password&client_id=admin-cli&username=" + enc("staff.dev@medlink.local")
                + "&password=" + enc(DEV_USERS_PASSWORD));
    }

    private static String token(String form) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create(keycloakUrl() + "/realms/medlink/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("Keycloak issues the token").isEqualTo(200);
        Matcher token = ACCESS_TOKEN.matcher(response.body());
        assertThat(token.find()).isTrue();
        return token.group(1);
    }

    private static HttpResponse<String> adminApi(String method, String path, String token) throws Exception {
        return adminApi(method, path, token, null);
    }

    private static HttpResponse<String> adminApi(String method, String path, String token, String json)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(keycloakUrl() + "/admin/realms/medlink" + path))
                .header("Authorization", "Bearer " + token);
        if (json == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
