package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * GitHub #51 (MLink-112): limits on the public auth endpoints that hold under parallel requests and
 * across API instances (the counts live in Postgres), and never tell whether an account exists.
 *
 * <p>Per IP: login, register and resend answer 429 {@code TOO_MANY_REQUESTS} past the limit, the same
 * answer whatever the email. Per account: at most one warning mail per taken address and one
 * verification mail per minute, silently (the usual 201 / 202).
 */
class RateLimitFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Limit-Pass-2026!";
    static final String TOO_MANY = "TOO_MANY_REQUESTS";
    static final String WARNING_SUBJECT = "Tentative d'inscription avec votre adresse e-mail MedLink";
    static final String VERIFY_SUBJECT = "Confirmez votre adresse e-mail MedLink";

    @Value("${app.rate-limit.login.max:30}")
    int loginMax;

    @Value("${app.rate-limit.register.max:20}")
    int registerMax;

    @Value("${app.rate-limit.resend.max:20}")
    int resendMax;

    @Autowired
    JdbcTemplate db;

    final KeycloakConsole console = new KeycloakConsole();
    final Mailbox mailbox = new Mailbox();
    final JsonMapper json = JsonMapper.builder().build();

    private static String login(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private static String signup(String email) {
        return """
                {"email":"%s","password":"%s","firstName":"Amel","lastName":"Ben Salah","birthDate":"1990-04-12"}
                """.formatted(email, PASSWORD);
    }

    private static String resend(String email) {
        return "{\"email\":\"" + email + "\"}";
    }

    /** The same post, {@code times} at once from one browser; the statuses of every answer. */
    private static List<Integer> burst(Browser browser, String path, String body, int times) throws Exception {
        browser.call("/api/auth/csrf");
        ExecutorService pool = Executors.newFixedThreadPool(times);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> answers = new ArrayList<>();
            for (int i = 0; i < times; i++) {
                Callable<Integer> post = () -> {
                    start.await();
                    return browser.post(path, body, true).statusCode();
                };
                answers.add(pool.submit(post));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> answer : answers) {
                statuses.add(answer.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long count(List<Integer> statuses, int status) {
        return statuses.stream().filter(s -> s == status).count();
    }

    private Map<?, ?> body(HttpResponse<String> response) {
        return json.readValue(response.body(), Map.class);
    }

    /** A 429 as the contract says: our error body, a generic message, and when to try again. */
    private void assertTooMany(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(body(response).get("code")).isEqualTo(TOO_MANY);
        assertThat(response.headers().firstValue("Retry-After")).hasValueSatisfying(
                seconds -> assertThat(Long.parseLong(seconds)).isPositive());
    }

    /** The 429 for one email and for another are the same answer (the timestamp aside). */
    private void assertSameAnswer(HttpResponse<String> first, HttpResponse<String> second) {
        Map<?, ?> a = body(first);
        Map<?, ?> b = body(second);
        assertThat(b.get("status")).isEqualTo(a.get("status"));
        assertThat(b.get("code")).isEqualTo(a.get("code"));
        assertThat(b.get("message")).isEqualTo(a.get("message"));
        assertThat(b.get("details")).isEqualTo(a.get("details"));
    }

    private void waitForMails(String email, String subject, int atLeast) throws InterruptedException {
        for (int i = 0; i < 50 && mailbox.to(email, subject).size() < atLeast; i++) {
            Thread.sleep(100);
        }
    }

    @Test
    void parallelLoginsFromOneIpNeverPassTheLimitAndOtherIpsAreNotAffected() throws Exception {
        Browser attacker = new Browser();

        List<Integer> statuses = burst(attacker, "/api/auth/login", login(uniqueEmail("nobody"), "Wrong-2026!"), loginMax + 5);

        assertThat(count(statuses, 401)).as("Keycloak asked exactly the limit").isEqualTo(loginMax);
        assertThat(count(statuses, 429)).isEqualTo(5);
        Browser someoneElse = new Browser();
        someoneElse.call("/api/auth/csrf");
        assertThat(someoneElse.post("/api/auth/login", login(uniqueEmail("nobody"), "Wrong-2026!"), true).statusCode())
                .as("another IP keeps its own count").isEqualTo(401);
    }

    @Test
    void aLimitedLoginAnswersTheSameForARealAccountWithItsRightPasswordAndForAnUnknownEmail() throws Exception {
        String email = uniqueEmail("limited");
        console.createUser(email, PASSWORD);
        Browser browser = new Browser();
        burst(browser, "/api/auth/login", login(uniqueEmail("nobody"), "Wrong-2026!"), loginMax);

        HttpResponse<String> real = browser.post("/api/auth/login", login(email, PASSWORD), true);
        HttpResponse<String> unknown = browser.post("/api/auth/login", login(uniqueEmail("nobody"), PASSWORD), true);

        assertTooMany(real);
        assertTooMany(unknown);
        assertSameAnswer(real, unknown);
        assertThat(browser.cookies.value("JSESSIONID")).as("no session opened past the limit").isNull();
    }

    @Test
    void parallelSignUpsWithATakenEmailStopAtTheLimitAndWarnTheOwnerOnce() throws Exception {
        String email = uniqueEmail("taken");
        assertThat(new Browser().postJson("/api/auth/register", signup(email)).statusCode()).isEqualTo(201);
        Browser browser = new Browser();

        List<Integer> statuses = burst(browser, "/api/auth/register", signup(email), registerMax + 5);

        assertThat(count(statuses, 201)).isEqualTo(registerMax);
        assertThat(count(statuses, 429)).isEqualTo(5);
        waitForMails(email, WARNING_SUBJECT, 1);
        assertThat(mailbox.to(email, WARNING_SUBJECT)).as("one warning, however many tries at once").hasSize(1);
        HttpResponse<String> taken = browser.post("/api/auth/register", signup(email), true);
        HttpResponse<String> fresh = browser.post("/api/auth/register", signup(uniqueEmail("fresh")), true);
        assertTooMany(taken);
        assertTooMany(fresh);
        assertSameAnswer(taken, fresh);
    }

    @Test
    void parallelResendsSendOneNewLinkAndStopAtTheLimit() throws Exception {
        String email = uniqueEmail("resend");
        assertThat(new Browser().postJson("/api/auth/register", signup(email)).statusCode()).isEqualTo(201);
        waitForMails(email, VERIFY_SUBJECT, 1);
        // The first link is two minutes old: a resend is due
        db.update("update email_tokens set created_at = now() - interval '2 minutes'"
                + " where user_id = (select id from users where email = ?)", email);
        Browser browser = new Browser();

        List<Integer> statuses = burst(browser, "/api/auth/verify-email/resend", resend(email), resendMax + 5);

        assertThat(count(statuses, 202)).isEqualTo(resendMax);
        assertThat(count(statuses, 429)).isEqualTo(5);
        waitForMails(email, VERIFY_SUBJECT, 2);
        assertThat(mailbox.to(email, VERIFY_SUBJECT)).as("the first mail and one new link").hasSize(2);
        HttpResponse<String> known = browser.post("/api/auth/verify-email/resend", resend(email), true);
        HttpResponse<String> unknown = browser.post("/api/auth/verify-email/resend", resend(uniqueEmail("nobody")), true);
        assertTooMany(known);
        assertTooMany(unknown);
        assertSameAnswer(known, unknown);
    }

    @Test
    void aForgedForwardedAddressDoesNotEscapeTheLimit() throws Exception {
        // Behind the proxy, the client's real IP is the last entry; anything before it is the client's own words
        String realIp = Browser.nextIp();
        for (int i = 0; i < registerMax; i++) {
            Browser forging = new Browser(Browser.nextIp() + ", " + realIp);
            forging.call("/api/auth/csrf");
            assertThat(forging.post("/api/auth/register", "{}", true).statusCode()).isEqualTo(400);
        }

        Browser forging = new Browser(Browser.nextIp() + ", " + realIp);
        forging.call("/api/auth/csrf");
        assertTooMany(forging.post("/api/auth/register", "{}", true));
    }
}
