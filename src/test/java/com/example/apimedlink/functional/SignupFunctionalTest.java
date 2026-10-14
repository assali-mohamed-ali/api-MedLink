package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.model.Patient;
import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.UserRepository;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Patient sign-up end to end: React's calls over HTTP, a real Keycloak, a real mail server
 * (Mailpit), a real database. Answers the code owner's review on #32: the email is verified for
 * real (comment 3), a taken email is never revealed (comment 1, MLink-79), and a patient is a
 * user (comment 4, MLink-78).
 */
class SignupFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Signup-Pass-2026!";
    static final String CREATED = "{\"message\":\"Vérifiez votre boîte mail pour activer votre compte.\"}";
    static final String WARNING_SUBJECT = "Tentative d'inscription avec votre adresse e-mail MedLink";

    @Autowired
    UserRepository users;

    @Autowired
    TransactionTemplate tx;

    final KeycloakConsole console = new KeycloakConsole();
    final Mailbox mailbox = new Mailbox();

    private static String signup(String email, String firstName) {
        return """
                {"email":"%s","password":"%s","firstName":"%s","lastName":"Ben Salah","birthDate":"1990-04-12"}
                """.formatted(email, PASSWORD, firstName);
    }

    private List<?> keycloakUsersWith(String email) {
        return (List<?>) console.read("/users?email={email}&exact=true", email);
    }

    private UUID keycloakId(String email) {
        return UUID.fromString((String) ((Map<?, ?>) keycloakUsersWith(email).get(0)).get("id"));
    }

    @Test
    void aVisitorSignsUpGetsTheMailAndOnlyTheLinkVerifiesTheEmail() throws Exception {
        String email = uniqueEmail("signup");
        Browser browser = new Browser();

        HttpResponse<String> created = browser.postJson("/api/auth/register", signup(email, "Amel"));

        assertThat(created.statusCode()).isEqualTo(201);
        UUID id = keycloakId(email);
        assertThat(((Map<?, ?>) console.read("/users/{id}", id)).get("emailVerified")).as("not verified before the link").isEqualTo(false);

        String token = mailbox.verificationToken(email);
        assertThat(browser.postJson("/api/auth/verify-email", "{\"token\":\"" + token + "\"}").statusCode()).isEqualTo(204);

        assertThat(((Map<?, ?>) console.read("/users/{id}", id)).get("emailVerified")).isEqualTo(true);
        assertThat(browser.postJson("/api/auth/verify-email", "{\"token\":\"" + token + "\"}").statusCode())
                .as("the link works once").isEqualTo(410);
    }

    @Test
    void theNewAccountIsOnePatientUnderTheKeycloakId() throws Exception {
        String email = uniqueEmail("patient");

        new Browser().postJson("/api/auth/register", signup(email, "Amel"));

        UUID id = keycloakId(email);
        tx.executeWithoutResult(status -> {
            Object user = users.findById(id).orElseThrow(); // a User row, which must be a Patient
            assertThat(user).isInstanceOf(Patient.class);
            assertThat(((User) user).getEmail()).isEqualTo(email);
            Patient patient = (Patient) user;
            assertThat(patient.getFirstName()).isEqualTo("Amel");
        });
    }

    @Test
    void aTakenEmailGetsTheSameAnswerNoSecondAccountAndTheOwnerIsWarned() throws Exception {
        String email = uniqueEmail("taken");
        HttpResponse<String> first = new Browser().postJson("/api/auth/register", signup(email, "Amel"));

        HttpResponse<String> second = new Browser().postJson("/api/auth/register", signup(email, "Intrus"));

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).as("same status as a new account").isEqualTo(201);
        assertThat(second.body()).as("same body: nothing tells the email is taken").isEqualTo(first.body()).isEqualTo(CREATED);
        assertThat(keycloakUsersWith(email)).hasSize(1);
        assertThat(mailbox.to(email, WARNING_SUBJECT)).as("the real owner is told").hasSize(1);
        assertThat(mailbox.to(email, "Confirmez votre adresse e-mail MedLink")).as("no second verification mail").hasSize(1);
    }

    @Test
    void anEmailKnownOnlyToKeycloakIsTreatedTheSame() throws Exception {
        String email = uniqueEmail("console-only");
        console.createUser(email, PASSWORD);

        HttpResponse<String> response = new Browser().postJson("/api/auth/register", signup(email, "Amel"));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).isEqualTo(CREATED);
        assertThat(keycloakUsersWith(email)).hasSize(1);
        assertThat(users.findByEmail(email)).as("no row created").isEmpty();
        assertThat(mailbox.to(email, WARNING_SUBJECT)).hasSize(1);
    }

    @Test
    void repeatedTriesOnATakenEmailSendOneWarningNotAFlood() throws Exception {
        String email = uniqueEmail("flood");
        new Browser().postJson("/api/auth/register", signup(email, "Amel"));

        for (int i = 0; i < 3; i++) {
            new Browser().postJson("/api/auth/register", signup(email, "Intrus"));
        }

        assertThat(mailbox.to(email, WARNING_SUBJECT)).hasSize(1);
    }
}
