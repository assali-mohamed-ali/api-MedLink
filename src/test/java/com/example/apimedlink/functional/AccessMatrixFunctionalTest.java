package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * MLink-60 (story MLink-16): who may call what. One line per /api route; a route without a line
 * fails the build, so nobody adds an endpoint and forgets its access rule.
 */
class AccessMatrixFunctionalTest extends FunctionalTestBase {

    enum Expect { OPEN, LOGIN, FORBIDDEN } // OPEN: past security, no server error (2xx-4xx but 401/403) · LOGIN: 401 · FORBIDDEN: 403

    record Rule(String method, String path, Expect anonymous, Expect patient, Expect practitioner, Expect staff) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    static final Expect O = Expect.OPEN, L = Expect.LOGIN, F = Expect.FORBIDDEN;

    //                 method    path                                anonymous patient practitioner staff
    static final List<Rule> MATRIX = List.of(
            new Rule("GET",    "/api/auth/csrf",                O, O, O, O),
            new Rule("POST",   "/api/auth/register",            O, O, O, O),
            new Rule("POST",   "/api/auth/verify-email",        O, O, O, O),
            new Rule("POST",   "/api/auth/verify-email/resend", O, O, O, O),
            new Rule("POST",   "/api/auth/login",               O, O, O, O),
            new Rule("POST",   "/api/auth/logout",              L, O, O, O),
            new Rule("POST",   "/api/auth/password",            L, O, O, O),
            // Only a pending code step (staff login, password accepted) gets past these; a full session does not
            new Rule("POST",   "/api/auth/mfa/setup",           L, L, L, L),
            new Rule("POST",   "/api/auth/mfa/verify",          L, L, L, L),
            new Rule("POST",   "/api/admin/users/{id}/force-password-reset", L, F, F, O),
            new Rule("GET",    "/api/users",                    L, F, F, O),
            new Rule("GET",    "/api/users/{id}",               L, F, F, O),
            new Rule("DELETE", "/api/users/{id}",               L, F, F, O),
            new Rule("GET",    "/api/users/_health",            L, O, O, O));

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void everyApiRouteHasALineInTheMatrix() {
        Set<String> routes = mappings.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream()
                        .filter(path -> path.startsWith("/api/"))
                        .flatMap(path -> info.getMethodsCondition().getMethods().isEmpty()
                                ? Stream.of("ANY " + path) // no method given: must be listed too
                                : info.getMethodsCondition().getMethods().stream().map(method -> method.name() + " " + path)))
                .collect(Collectors.toSet());
        Set<String> inMatrix = MATRIX.stream().map(Rule::toString).collect(Collectors.toSet());

        assertThat(inMatrix).as("one matrix line per /api route, no more, no less").isEqualTo(routes);
    }

    enum Who { ANONYMOUS, PATIENT, PRACTITIONER, STAFF }

    /** Every cell of the matrix: each route × 4 callers. */
    static Stream<Arguments> cells() {
        return MATRIX.stream().flatMap(rule -> Stream.of(
                Arguments.of(rule, Who.ANONYMOUS, rule.anonymous()),
                Arguments.of(rule, Who.PATIENT, rule.patient()),
                Arguments.of(rule, Who.PRACTITIONER, rule.practitioner()),
                Arguments.of(rule, Who.STAFF, rule.staff())));
    }

    @ParameterizedTest(name = "{1} {0} → {2}")
    @MethodSource("cells")
    void eachCallerGetsWhatTheMatrixSays(Rule rule, Who who, Expect expect) throws Exception {
        HttpResponse<String> response = send(as(who), rule.method(), rule.path());
        int status = response.statusCode();

        // The browser follows redirects: an answer that came through one is never what the matrix expects
        assertThat(response.previousResponse()).as("no redirect").isEmpty();
        switch (expect) {
            // Past security, and the endpoint did not break: a 500 must not pass for "allowed" (review on #77)
            case OPEN -> assertThat(status).as("past security, no server error").isBetween(200, 499).isNotIn(401, 403);
            case LOGIN -> assertThat(status).as("asked to log in").isEqualTo(401);
            case FORBIDDEN -> assertThat(status).as("refused by role or ownership").isEqualTo(403);
        }
    }

    /**
     * A fresh browser per cell (a logout cannot spoil the next one), logged in as the realm's dev user; a
     * practitioner or staff member through the code step too (MLink-62/63).
     */
    private static Browser as(Who who) throws Exception {
        Browser browser = new Browser();
        String email = switch (who) {
            case ANONYMOUS -> null;
            case PATIENT -> "patient.dev@medlink.local";
            case PRACTITIONER -> "practitioner.dev@medlink.local";
            case STAFF -> "staff.dev@medlink.local";
        };
        if (email != null) {
            assertThat(browser.login(email, DEV_USERS_PASSWORD).call("/api/users/_health").statusCode())
                    .as(who + " logged in").isEqualTo(200);
        }
        return browser;
    }

    /**
     * Calls a route as React would, always with the CSRF header: a 403 here is a role refusal, never a
     * missing token. {id} is a random UUID (someone else's); writes send an empty JSON body.
     */
    private static HttpResponse<String> send(Browser browser, String method, String path) throws Exception {
        if (browser.cookies.value("XSRF-TOKEN") == null) {
            browser.call("/api/auth/csrf");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(appUrl(path.replace("{id}", UUID.randomUUID().toString()))))
                .header("X-Forwarded-For", browser.forwardedFor)
                .header("X-XSRF-TOKEN", browser.cookies.value("XSRF-TOKEN"))
                .header("Content-Type", "application/json")
                .method(method, method.equals("GET")
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        return browser.http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
