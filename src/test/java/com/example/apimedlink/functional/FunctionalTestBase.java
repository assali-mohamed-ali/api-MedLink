package com.example.apimedlink.functional;

import com.example.apimedlink.TestcontainersConfiguration;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.HttpCookie;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Functional tests: the real API on a real port, a real Keycloak (same image and realm file as
 * dev), a real database. A test acts like a browser (cookies, redirects, the Keycloak login form)
 * and like an admin in the Keycloak console (its admin REST API). Nothing is mocked.
 *
 * <p>One Keycloak for every functional test class: subclasses share the container and the Spring
 * context, so it starts once per test run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(TestcontainersConfiguration.class)
public abstract class FunctionalTestBase {

    static final String BFF_SECRET = "functional-bff-secret";
    static final String ADMIN_CLIENT_SECRET = "functional-admin-secret";
    static final int APP_PORT = freePort();

    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.7.5")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withEnv("KC_BFF_CLIENT_SECRET", BFF_SECRET)
            .withEnv("KC_ADMIN_CLIENT_SECRET", ADMIN_CLIENT_SECRET)
            .withEnv("KC_BFF_BASE_URL", "http://localhost:" + APP_PORT)
            .withEnv("KC_DEV_USERS_PASSWORD", "Functional-Dev-2026!")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("keycloak/realm-medlink.json"),
                    "/opt/keycloak/data/import/realm-medlink.json")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/medlink").forPort(8080).withStartupTimeout(Duration.ofMinutes(4)));

    static {
        KEYCLOAK.start();
    }

    @DynamicPropertySource
    static void appProperties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> APP_PORT);
        registry.add("keycloak.public-url", FunctionalTestBase::keycloakUrl);
        registry.add("keycloak.internal-url", FunctionalTestBase::keycloakUrl);
        registry.add("keycloak.client-secret", () -> BFF_SECRET);
        registry.add("keycloak.admin.client-secret", () -> ADMIN_CLIENT_SECRET);
        // Renew the access token on every call (any skew above its 5-minute life), so a test sees
        // at its next call what production sees within 5 minutes
        registry.add("app.session.token-renewal-skew", () -> "10m");
    }

    static String keycloakUrl() {
        return "http://localhost:" + KEYCLOAK.getMappedPort(8080);
    }

    static String appUrl(String path) {
        return "http://localhost:" + APP_PORT + path;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("no free port for the API", e);
        }
    }

    // ---- the browser ---------------------------------------------------------------------------

    /** A browser: keeps cookies (API session, Keycloak's own) and follows redirects. */
    static final class Browser {

        private static final Pattern LOGIN_FORM = Pattern.compile("action=\"([^\"]*login-actions/authenticate[^\"]*)\"");

        final HttpClient http = HttpClient.newBuilder()
                .cookieHandler(new LocalhostCookieJar())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        /** Logs in through the API exactly as a person does: API redirect, Keycloak form, back to the API. */
        Browser login(String username, String password) throws Exception {
            String page = http.send(get(appUrl("/oauth2/authorization/keycloak")), HttpResponse.BodyHandlers.ofString()).body();
            Matcher form = LOGIN_FORM.matcher(page);
            if (!form.find()) {
                throw new AssertionError("Keycloak login form not found");
            }
            String action = form.group(1).replace("&amp;", "&");
            String body = "username=" + enc(username) + "&password=" + enc(password) + "&credentialId=";
            http.send(HttpRequest.newBuilder(URI.create(action))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(), HttpResponse.BodyHandlers.discarding());
            return this;
        }

        HttpResponse<String> call(String path) throws Exception {
            return http.send(get(appUrl(path)), HttpResponse.BodyHandlers.ofString());
        }

        private static HttpRequest get(String url) {
            return HttpRequest.newBuilder(URI.create(url)).GET().build();
        }

        private static String enc(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }

    /**
     * Cookies for one host, by name. Java's CookieManager files cookies set by "localhost" under
     * "localhost.local" and never sends them back, which breaks Keycloak's login form. The API and
     * Keycloak both run on localhost here, so one jar by name is enough.
     */
    static final class LocalhostCookieJar extends CookieHandler {

        private final Map<String, String> cookies = new ConcurrentHashMap<>();

        @Override
        public Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders) {
            if (cookies.isEmpty()) {
                return Map.of();
            }
            String header = cookies.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("; "));
            return Map.of("Cookie", List.of(header));
        }

        @Override
        public void put(URI uri, Map<String, List<String>> responseHeaders) {
            responseHeaders.forEach((name, values) -> {
                if (name != null && name.equalsIgnoreCase("Set-Cookie")) {
                    values.forEach(this::store);
                }
            });
        }

        private void store(String setCookie) {
            for (HttpCookie cookie : HttpCookie.parse(setCookie)) {
                if (cookie.getMaxAge() == 0) {
                    cookies.remove(cookie.getName());
                } else {
                    cookies.put(cookie.getName(), cookie.getValue());
                }
            }
        }
    }

    // ---- the Keycloak console ------------------------------------------------------------------

    /** What an admin does in the Keycloak console, through its admin REST API (master admin). */
    static final class KeycloakConsole {

        private final RestClient http = RestClient.builder().baseUrl(keycloakUrl()).build();

        private String token() {
            Map<?, ?> response = http.post()
                    .uri("/realms/master/protocol/openid-connect/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=password&client_id=admin-cli&username=admin&password=admin")
                    .retrieve()
                    .body(Map.class);
            return "Bearer " + response.get("access_token");
        }

        /** A patient created by hand in the console: enabled, email verified, permanent password. */
        UUID createUser(String email, String password) {
            URI location = http.post().uri("/admin/realms/medlink/users")
                    .header(HttpHeaders.AUTHORIZATION, token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "username", email, "email", email, "firstName", "Console", "lastName", "User",
                            "enabled", true, "emailVerified", true, "groups", List.of("/patients"),
                            "credentials", List.of(Map.of("type", "password", "value", password, "temporary", false))))
                    .retrieve()
                    .toBodilessEntity()
                    .getHeaders()
                    .getLocation();
            String path = location.getPath();
            return UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
        }

        void update(UUID id, Map<String, Object> fields) {
            http.put().uri("/admin/realms/medlink/users/{id}", id)
                    .header(HttpHeaders.AUTHORIZATION, token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(fields)
                    .retrieve()
                    .toBodilessEntity();
        }

        void disable(UUID id) {
            update(id, Map.of("enabled", false));
        }

        /** The console's "Sign out" on a user: ends all their Keycloak sessions. */
        void signOutEverywhere(UUID id) {
            http.post().uri("/admin/realms/medlink/users/{id}/logout", id)
                    .header(HttpHeaders.AUTHORIZATION, token())
                    .retrieve()
                    .toBodilessEntity();
        }
    }

    static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }
}
