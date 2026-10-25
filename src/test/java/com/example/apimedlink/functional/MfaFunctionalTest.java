package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.service.Totp;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Sub-sprint 3 (MLink-62/63, plan.md §4 endpoints 5, 8 and 9): a practitioner or staff member logs in with the
 * password, then a code from their authenticator app. The password alone opens no session; the first login
 * enrols the code through a QR code; 5 wrong codes lock the code step for 15 minutes. Each test uses its own
 * console-made users, so a lock never reaches the realm's dev users.
 */
class MfaFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Mfa-Functional-2026!";

    final KeycloakConsole console = new KeycloakConsole();

    @Autowired
    JdbcTemplate jdbc;

    private String newPractitioner() {
        String email = uniqueEmail("practitioner");
        console.createUser(email, PASSWORD, "/practitioners");
        return email;
    }

    /** A practitioner whose code is enrolled (first login done and logged out); its secret is in CODE_SECRETS. */
    private String enrolledPractitioner() throws Exception {
        String email = newPractitioner();
        new Browser().login(email, PASSWORD).logout();
        return email;
    }

    private static byte[] secretOf(String email) {
        return Browser.CODE_SECRETS.get(email.toLowerCase());
    }

    /** A browser whose password Keycloak accepted, now at the code step. */
    private static Browser atCodeStep(String email) throws Exception {
        Browser browser = new Browser();
        Map<?, ?> answer = Browser.json(browser.apiLogin(email, PASSWORD));
        assertThat(answer.get("mfaRequired")).as("at the code step").isEqualTo(true);
        return browser;
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }

    private static void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(Browser.json(response).get("code")).isEqualTo(code);
    }

    private static int sessionCheck(Browser browser) throws Exception {
        return browser.call("/api/users/_health").statusCode();
    }

    // ---- endpoint 5, the staff branch ----------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"/practitioners", "/administrative-staff"})
    void thePasswordAloneAsksForTheCodeAndOpensNoSession(String group) throws Exception {
        String email = uniqueEmail("staff");
        console.createUser(email, PASSWORD, group);
        Browser browser = new Browser();

        HttpResponse<String> login = browser.apiLogin(email, PASSWORD);

        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(Browser.json(login)).isEqualTo(Map.of("mfaRequired", true, "mfaSetupRequired", true));
        assertThat(sessionCheck(browser)).as("no session before the code").isEqualTo(401);
    }

    @Test
    void onceEnrolledTheLoginAsksForTheCodeOnly() throws Exception {
        String email = enrolledPractitioner();

        HttpResponse<String> login = new Browser().apiLogin(email, PASSWORD);

        assertThat(Browser.json(login)).isEqualTo(Map.of("mfaRequired", true, "mfaSetupRequired", false));
    }

    @Test
    void aPatientLogsInWithThePasswordAsBefore() throws Exception {
        Browser browser = new Browser();

        Map<?, ?> answer = Browser.json(browser.apiLogin("patient.dev@medlink.local", DEV_USERS_PASSWORD));

        assertThat(answer.containsKey("user")).as("a full login").isTrue();
        assertThat(answer.containsKey("mfaRequired")).isFalse();
        assertThat(sessionCheck(browser)).isEqualTo(200);
    }

    // ---- endpoint 8, setup -----------------------------------------------------------------------

    @Test
    void setupGivesTheQrCodeAndStoresTheSecretSealed() throws Exception {
        String email = newPractitioner();
        Browser browser = atCodeStep(email);

        HttpResponse<String> setup = browser.mfaSetup();

        assertThat(setup.statusCode()).isEqualTo(200);
        Map<?, ?> body = Browser.json(setup);
        String secret = (String) body.get("secret");
        assertThat(secret).matches("[A-Z2-7]{32}");
        assertThat((String) body.get("otpauthUrl")).startsWith("otpauth://totp/MedLink:").contains("secret=" + secret);
        assertThat((String) body.get("qrCode")).startsWith("data:image/png;base64,");
        String stored = jdbc.queryForObject(
                "SELECT m.secret_enc FROM user_mfa m JOIN users u ON u.id = m.user_id WHERE u.email = ?",
                String.class, email);
        assertThat(stored).doesNotContain(secret);
        assertThat(sessionCheck(browser)).as("still no session").isEqualTo(401);
    }

    @Test
    void setupWithoutAPendingCodeStepIsRefused() throws Exception {
        assertError(new Browser().postJson("/api/auth/mfa/setup", ""), 401, "UNAUTHORIZED");
    }

    @Test
    void setupOnceEnrolledIsRefused() throws Exception {
        String email = enrolledPractitioner();

        assertError(atCodeStep(email).mfaSetup(), 409, "MFA_ALREADY_ENROLLED");
    }

    @Test
    void aSecondSetupBeforeTheFirstCodeReplacesTheSecret() throws Exception {
        Browser browser = atCodeStep(newPractitioner());
        byte[] first = Totp.fromBase32((String) Browser.json(browser.mfaSetup()).get("secret"));
        byte[] second = Totp.fromBase32((String) Browser.json(browser.mfaSetup()).get("secret"));

        assertError(browser.mfaVerify(CLOCK.nextCode(first)), 401, "INVALID_CODE");
        assertThat(browser.mfaVerify(CLOCK.nextCode(second)).statusCode()).isEqualTo(200);
    }

    // ---- endpoint 9, verify ----------------------------------------------------------------------

    @Test
    void theRightCodeOpensTheSessionWithTheRole() throws Exception {
        String email = newPractitioner();
        Browser browser = atCodeStep(email);
        byte[] secret = Totp.fromBase32((String) Browser.json(browser.mfaSetup()).get("secret"));
        String pendingSession = browser.cookies.value("JSESSIONID");

        HttpResponse<String> verify = browser.mfaVerify(CLOCK.nextCode(secret));

        assertThat(verify.statusCode()).isEqualTo(200);
        Map<?, ?> user = (Map<?, ?>) Browser.json(verify).get("user");
        assertThat(user.get("email")).isEqualTo(email);
        assertThat(user.get("roles")).isEqualTo(List.of("PRACTITIONER"));
        assertThat(sessionCheck(browser)).isEqualTo(200);
        assertThat(browser.cookies.value("JSESSIONID")).as("a new session id").isNotEqualTo(pendingSession);
    }

    @Test
    void aCodeWorksOnce() throws Exception {
        String email = enrolledPractitioner();
        String code = CLOCK.nextCode(secretOf(email));
        assertThat(atCodeStep(email).mfaVerify(code).statusCode()).isEqualTo(200);

        Browser again = atCodeStep(email);

        assertError(again.mfaVerify(code), 401, "INVALID_CODE");
        assertThat(sessionCheck(again)).isEqualTo(401);
    }

    @Test
    void aWrongCodeIsRefusedAndTheStepGoesOn() throws Exception {
        String email = enrolledPractitioner();
        Browser browser = atCodeStep(email);
        String code = CLOCK.nextCode(secretOf(email));

        assertError(browser.mfaVerify(wrong(code)), 401, "INVALID_CODE");
        assertThat(sessionCheck(browser)).isEqualTo(401);
        assertThat(browser.mfaVerify(code).statusCode()).as("the right code still works").isEqualTo(200);
    }

    @Test
    void fiveWrongCodesLockTheCodeStepAndEndTheKeycloakSession() throws Exception {
        String email = enrolledPractitioner();
        UUID id = UUID.fromString(jdbc.queryForObject("SELECT id FROM users WHERE email = ?", String.class, email));
        Browser browser = atCodeStep(email);
        String code = CLOCK.nextCode(secretOf(email));
        for (int i = 1; i <= 4; i++) {
            assertError(browser.mfaVerify(wrong(code)), 401, "INVALID_CODE");
        }

        assertError(browser.mfaVerify(wrong(code)), 423, "MFA_LOCKED");

        assertError(browser.mfaVerify(code), 401, "UNAUTHORIZED"); // the pending step is gone: log in again
        assertThat(console.sessions(id)).as("Keycloak session ended").isEmpty();
    }

    @Test
    void theLockHoldsAcrossLoginsForFifteenMinutes() throws Exception {
        String email = enrolledPractitioner();
        Browser first = atCodeStep(email);
        String code = CLOCK.nextCode(secretOf(email));
        for (int i = 1; i <= 5; i++) {
            first.mfaVerify(wrong(code));
        }

        assertError(atCodeStep(email).mfaVerify(CLOCK.nextCode(secretOf(email))), 423, "MFA_LOCKED");

        CLOCK.advance(Duration.ofMinutes(15));
        Browser later = atCodeStep(email);
        assertThat(later.mfaVerify(CLOCK.nextCode(secretOf(email))).statusCode()).isEqualTo(200);
        assertThat(sessionCheck(later)).isEqualTo(200);
    }

    @Test
    void verifyWithoutAPendingCodeStepIsRefused() throws Exception {
        assertError(new Browser().postJson("/api/auth/mfa/verify", "{\"code\":\"123456\"}"), 401, "UNAUTHORIZED");
    }

    @Test
    void theCodeStepEndsAfterFiveMinutes() throws Exception {
        String email = enrolledPractitioner();
        Browser browser = atCodeStep(email);

        CLOCK.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertError(browser.mfaVerify(Totp.code(secretOf(email), Totp.step(CLOCK.instant()))), 401, "UNAUTHORIZED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "1234567", "abcdef", ""})
    void aCodeThatIsNotSixDigitsIsAFormError(String code) throws Exception {
        Browser browser = atCodeStep(enrolledPractitioner());

        assertError(browser.mfaVerify(code), 400, "VALIDATION_FAILED");
    }
}
