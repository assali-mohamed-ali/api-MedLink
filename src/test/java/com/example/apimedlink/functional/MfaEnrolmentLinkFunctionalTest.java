package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.UserRepository;
import com.example.apimedlink.service.MfaService;
import com.example.apimedlink.service.Totp;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Code owner's review of #97 (MLink-62): the password alone must not enrol a staff code, or a stolen password
 * would let anyone bind their own authenticator app first. A practitioner's or staff member's first setup needs
 * a single-use link mailed to their own address, valid 30 minutes; at most one such mail per 15 minutes.
 */
class MfaEnrolmentLinkFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Mfa-Enrolment-2026!";

    final KeycloakConsole console = new KeycloakConsole();

    final Mailbox mailbox = new Mailbox();

    @Autowired
    UserRepository users;

    @Autowired
    MfaService mfa;

    @Autowired
    JdbcTemplate jdbc;

    private String newPractitioner() {
        String email = uniqueEmail("enrolment");
        console.createUser(email, PASSWORD, "/practitioners");
        return email;
    }

    private int enrolmentMails(String email) {
        return mailbox.to(email, Mailbox.ENROLMENT_SUBJECT).size();
    }

    // ---- the mail (endpoint 5, staff branch) ---------------------------------------------------

    @Test
    void aLoginThatNeedsTheSetupMailsOneLinkToTheAccountsAddress() throws Exception {
        String email = newPractitioner();

        HttpResponse<String> login = new Browser().apiLogin(email, PASSWORD);

        assertThat(Browser.json(login)).as("the answer is unchanged")
                .isEqualTo(Map.of("mfaRequired", true, "mfaSetupRequired", true));
        assertThat(enrolmentMails(email)).isEqualTo(1);
        assertThat(mailbox.enrolmentToken(email)).matches("[A-Za-z0-9_-]{43}");
        assertThat(mailbox.text(mailbox.to(email, Mailbox.ENROLMENT_SUBJECT).get(0)))
                .contains("30 minutes").contains("mot de passe");
    }

    @Test
    void aSecondLoginWithinFifteenMinutesSendsNoSecondMail() throws Exception {
        String email = newPractitioner();
        new Browser().apiLogin(email, PASSWORD);

        HttpResponse<String> again = new Browser().apiLogin(email, PASSWORD);

        assertThat(Browser.json(again)).isEqualTo(Map.of("mfaRequired", true, "mfaSetupRequired", true));
        assertThat(enrolmentMails(email)).isEqualTo(1);
    }

    @Test
    void anEnrolledStaffMemberGetsNoMail() throws Exception {
        String email = uniqueEmail("enrolled");
        UUID id = console.createUser(email, PASSWORD, "/practitioners");
        users.save(User.builder().id(id).username(email).email(email).build());
        byte[] secret = Totp.fromBase32(mfa.startEnrolment(id, email).secret());
        mfa.verify(id, CLOCK.nextCode(secret)); // enrolled without any login, so without any mail

        HttpResponse<String> login = new Browser().apiLogin(email, PASSWORD);

        assertThat(Browser.json(login)).isEqualTo(Map.of("mfaRequired", true, "mfaSetupRequired", false));
        assertThat(enrolmentMails(email)).isZero();
    }

    @Test
    void aPatientGetsNoMail() throws Exception {
        String email = uniqueEmail("patient-no-enrolment");
        console.createUser(email, PASSWORD);

        new Browser().apiLogin(email, PASSWORD);

        assertThat(enrolmentMails(email)).isZero();
    }

    // ---- endpoint 8 needs the link: the takeover path ------------------------------------------

    /** A browser at the code step with the victim's password: what someone holding a stolen password has. */
    private static Browser withThePassword(String email) throws Exception {
        Browser browser = new Browser();
        assertThat(Browser.json(browser.apiLogin(email, PASSWORD)).get("mfaSetupRequired")).isEqualTo(true);
        return browser;
    }

    private boolean hasASecret(String email) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM user_mfa m JOIN users u ON u.id = m.user_id WHERE u.email = ?",
                Integer.class, email) > 0;
    }

    private static void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(Browser.json(response).get("code")).isEqualTo(code);
    }

    @Test
    void thePasswordWithoutTheMailedLinkEnrolsNothing() throws Exception {
        String victim = newPractitioner();
        Browser attacker = withThePassword(victim);

        assertError(attacker.mfaSetup(null), 400, "VALIDATION_FAILED");
        assertError(attacker.mfaSetup("made-up-token"), 400, "INVALID_TOKEN");

        assertThat(hasASecret(victim)).as("no secret stored").isFalse();
    }

    @Test
    void anotherStaffMembersLinkEnrolsNothing() throws Exception {
        String attackersOwn = newPractitioner();
        withThePassword(attackersOwn); // the attacker's own account mails the attacker a valid link
        String victim = newPractitioner();
        Browser attacker = withThePassword(victim);

        assertError(attacker.mfaSetup(mailbox.enrolmentToken(attackersOwn)), 400, "INVALID_TOKEN");

        assertThat(hasASecret(victim)).as("no secret stored").isFalse();
    }

    @Test
    void theMailedLinkEnrolsAndWorksAgainUntilTheFirstGoodCode() throws Exception {
        String email = newPractitioner();
        Browser browser = withThePassword(email);
        String token = mailbox.enrolmentToken(email);

        assertThat(browser.mfaSetup(token).statusCode()).isEqualTo(200);
        HttpResponse<String> again = browser.mfaSetup(token); // a reload before the first code: a new secret
        assertThat(again.statusCode()).isEqualTo(200);
        byte[] secret = Totp.fromBase32((String) Browser.json(again).get("secret"));
        assertThat(browser.mfaVerify(CLOCK.nextCode(secret)).statusCode()).isEqualTo(200);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_tokens t JOIN users u ON u.id = t.user_id"
                        + " WHERE u.email = ? AND t.purpose = 'MFA_ENROLMENT' AND t.used_at IS NULL",
                Integer.class, email)).as("the link is closed once the code works").isZero();
        Browser later = new Browser();
        later.apiLogin(email, PASSWORD);
        assertError(later.mfaSetup(token), 409, "MFA_ALREADY_ENROLLED"); // the contract's answer, as before
    }

    @Test
    void theFirstLinkStillWorksAfterASecondLoginWithinTheInterval() throws Exception {
        String email = newPractitioner();
        withThePassword(email);
        Browser second = withThePassword(email); // no new mail: the first link is the one to use

        assertThat(second.mfaSetup(mailbox.enrolmentToken(email)).statusCode()).isEqualTo(200);
    }

    @Test
    void withoutTheCodeStepTheLinkAloneIsRefused() throws Exception {
        String email = newPractitioner();
        withThePassword(email);
        Browser stranger = new Browser();
        stranger.call("/api/auth/csrf");

        assertError(stranger.mfaSetup(mailbox.enrolmentToken(email)), 401, "UNAUTHORIZED");
    }
}
