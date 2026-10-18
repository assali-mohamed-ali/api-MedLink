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
import java.util.concurrent.atomic.AtomicInteger;
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
 * dev), a real database. A test acts like a browser (cookies, our login endpoint, CSRF header)
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
    /** Password of the realm's dev users (staff.dev, practitioner.dev, patient.dev) in these tests. */
    static final String DEV_USERS_PASSWORD = "Functional-Dev-2026!";
    static final int APP_PORT = freePort();

    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.7.5")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withEnv("KC_BFF_CLIENT_SECRET", BFF_SECRET)
            .withEnv("KC_ADMIN_CLIENT_SECRET", ADMIN_CLIENT_SECRET)
            .withEnv("KC_BFF_BASE_URL", "http://localhost:" + APP_PORT)
            .withEnv("KC_DEV_USERS_PASSWORD", DEV_USERS_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath("keycloak/realm-medlink.json"),
                    "/opt/keycloak/data/import/realm-medlink.json")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/medlink").forPort(8080).withStartupTimeout(Duration.ofMinutes(4)));

    /** Catches every mail the API sends, like Mailpit in dev (same image). */
    static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:v1.27.10")
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/api/v1/info").forPort(8025));

    static {
        KEYCLOAK.start();
        MAILPIT.start();
    }

    @DynamicPropertySource
    static void appProperties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> APP_PORT);
        registry.add("keycloak.public-url", FunctionalTestBase::keycloakUrl);
        registry.add("keycloak.internal-url", FunctionalTestBase::keycloakUrl);
        registry.add("keycloak.client-secret", () -> BFF_SECRET);
        registry.add("keycloak.admin.client-secret", () -> ADMIN_CLIENT_SECRET);
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
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

    /**
     * A browser: keeps cookies (API session, CSRF) and follows redirects. Each one is a visitor with
     * its own IP: the test plays the reverse proxy (loopback, trusted) and sends it in
     * {@code X-Forwarded-For}, so the per-IP limits (GitHub #51) count each browser apart.
     */
    static final class Browser {

        private static final AtomicInteger NEXT_IP = new AtomicInteger();

        final LocalhostCookieJar cookies = new LocalhostCookieJar();

        /** What the proxy puts in {@code X-Forwarded-For}: the client's IP, last. */
        final String forwardedFor;

        Browser() {
            this(nextIp());
        }

        Browser(String forwardedFor) {
            this.forwardedFor = forwardedFor;
        }

        /** A new address in 198.18.0.0/15 (reserved for tests) for each browser of the run. */
        static String nextIp() {
            int n = NEXT_IP.getAndIncrement();
            return "198." + (18 + n / 65_536 % 2) + "." + (n / 256 % 256) + "." + (n % 256);
        }

        final HttpClient http = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        /** Logs in through our login endpoint (MLink-55), as a person does in React; the answer is not checked here. */
        Browser login(String email, String password) throws Exception {
            apiLogin(email, password);
            return this;
        }

        /** Logs in through our login endpoint (MLink-55), as React does: CSRF cookie first, then the JSON post. */
        HttpResponse<String> apiLogin(String email, String password) throws Exception {
            call("/api/auth/csrf");
            return post("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}", true);
        }
        /** Logs out as React does: a POST with the CSRF header, no body. */
        HttpResponse<String> logout() throws Exception {
            return post("/api/auth/logout", "", true);
        }

        /** A JSON post; {@code withCsrf} echoes the XSRF-TOKEN cookie in the X-XSRF-TOKEN header. */
        HttpResponse<String> post(String path, String json, boolean withCsrf) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(appUrl(path)))
                    .header("X-Forwarded-For", forwardedFor)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            String csrf = cookies.value("XSRF-TOKEN");
            if (withCsrf && csrf != null) {
                request.header("X-XSRF-TOKEN", csrf);
            }
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> call(String path) throws Exception {
            return http.send(get(appUrl(path)), HttpResponse.BodyHandlers.ofString());
        }

        /** A write from React: fetches the CSRF cookie once, then posts with it. */
        HttpResponse<String> postJson(String path, String json) throws Exception {
            if (cookies.value("XSRF-TOKEN") == null) {
                call("/api/auth/csrf");
            }
            return post(path, json, true);
        }

        private HttpRequest get(String url) {
            return HttpRequest.newBuilder(URI.create(url)).header("X-Forwarded-For", forwardedFor).GET().build();
        }
    }

    /**
     * Cookies for one host, by name. Java's CookieManager files cookies set by "localhost" under
     * "localhost.local" and never sends them back, which breaks Keycloak's login form. The API and
     * Keycloak both run on localhost here, so one jar by name is enough.
     */
    static final class LocalhostCookieJar extends CookieHandler {

        private final Map<String, String> cookies = new ConcurrentHashMap<>();

        /** The value of a cookie the browser holds, or null. */
        String value(String name) {
            return cookies.get(name);
        }

        /** Puts a cookie in the jar by hand, as if the server had set it (e.g. an old session id). */
        void set(String name, String value) {
            cookies.put(name, value);
        }

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
            return createUser(email, password, true);
        }

        /** Same, choosing whether the email is verified (an unverified one: a sign-up whose link was never opened). */
        UUID createUser(String email, String password, boolean emailVerified) {
            URI location = http.post().uri("/admin/realms/medlink/users")
                    .header(HttpHeaders.AUTHORIZATION, token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "username", email, "email", email, "firstName", "Console", "lastName", "User",
                            "enabled", true, "emailVerified", emailVerified, "groups", List.of("/patients"),
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

        /** A read in the admin API, as JSON (an object or a list). */
        Object read(String path, Object... uriVariables) {
            return http.get().uri("/admin/realms/medlink" + path, uriVariables)
                    .header(HttpHeaders.AUTHORIZATION, token())
                    .retrieve()
                    .body(Object.class);
        }

        /** The realm's settings, as the console shows them. */
        Map<?, ?> realm() {
            return (Map<?, ?>) read("");
        }

        /** The console's Events tab for one user and one type, newest first. */
        List<?> events(UUID user, String type) {
            return (List<?>) read("/events?user={user}&type={type}&max=50", user, type);
        }

        /** The user's open Keycloak sessions, as the console's Sessions tab lists them. */
        List<?> sessions(UUID user) {
            return (List<?>) read("/users/{id}/sessions", user);
        }

        /** Brute-force status of a user: {@code disabled} is true while locked out. */
        Map<?, ?> lockout(UUID user) {
            return (Map<?, ?>) read("/attack-detection/brute-force/users/{id}", user);
        }
    }

    /** The token endpoint, called server to server as the API's login will (password grant). */
    static HttpResponse<String> passwordGrant(String clientId, String secret, String username, String password)
            throws Exception {
        String body = "grant_type=password&client_id=" + enc(clientId) + "&client_secret=" + enc(secret)
                + "&username=" + enc(username) + "&password=" + enc(password) + "&scope=openid";
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create(keycloakUrl() + "/realms/medlink/protocol/openid-connect/token"))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ---- the mailbox ---------------------------------------------------------------------------

    /** The mails the API sent, read through Mailpit's API. */
    static final class Mailbox {

        private static final Pattern VERIFY_LINK = Pattern.compile("verifier-email[?]token=([A-Za-z0-9_-]+)");

        private final RestClient http =
                RestClient.builder().baseUrl("http://localhost:" + MAILPIT.getMappedPort(8025)).build();

        /** Mails sent to exactly this address (Mailpit's search also matches longer addresses), newest first. */
        List<Map<?, ?>> to(String email) {
            Map<?, ?> found = http.get().uri("/api/v1/search?query={q}", "to:\"" + email + "\"")
                    .retrieve().body(Map.class);
            return ((List<?>) found.get("messages")).stream()
                    .<Map<?, ?>>map(m -> (Map<?, ?>) m)
                    .filter(m -> ((List<?>) m.get("To")).stream()
                            .anyMatch(to -> email.equalsIgnoreCase((String) ((Map<?, ?>) to).get("Address"))))
                    .toList();
        }

        List<Map<?, ?>> to(String email, String subject) {
            return to(email).stream().filter(m -> subject.equals(m.get("Subject"))).toList();
        }

        String text(Map<?, ?> mail) {
            return (String) http.get().uri("/api/v1/message/{id}", mail.get("ID")).retrieve().body(Map.class).get("Text");
        }

        /** The token in the newest verification mail to this address. */
        String verificationToken(String email) {
            List<Map<?, ?>> mails = to(email, "Confirmez votre adresse e-mail MedLink");
            if (mails.isEmpty()) {
                throw new AssertionError("no verification mail to " + email);
            }
            Matcher link = VERIFY_LINK.matcher(text(mails.get(0)));
            if (!link.find()) {
                throw new AssertionError("no verification link in the mail to " + email);
            }
            return link.group(1);
        }
    }

    static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }
}
