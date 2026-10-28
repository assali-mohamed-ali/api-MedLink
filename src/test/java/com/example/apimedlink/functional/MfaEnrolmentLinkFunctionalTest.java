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
}
