package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.service.ConsentPolicy;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * US-04: the consent text is public; a sign-up that accepts the current text records its version and the time with the
 * patient; an old or unknown version is refused (400) and creates nothing. (The "required in production" rule is
 * covered by ConsentPolicyTest too.) Consent is required in every profile, so a sign-up without it is refused here as
 * in production, and the recorded version and time survive later profile changes.
 */
class ConsentFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Consent-Pass-2026!";

    final JsonMapper mapper = JsonMapper.builder().build();
    final KeycloakConsole console = new KeycloakConsole();

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @SuppressWarnings("unchecked")
    void theCurrentConsentTextIsPublic() throws Exception {
        HttpResponse<String> response = new Browser().call("/api/auth/consent");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        Map<String, Object> body = mapper.readValue(response.body(), Map.class);
        assertThat(body).containsKeys("version", "text", "required");
        assertThat((String) body.get("text")).contains("données personnelles et de santé");
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptingTheCurrentTextRecordsItsVersionAndTheTime() throws Exception {
        String email = uniqueEmail("consent");
        Browser browser = new Browser();
        String version = (String) mapper.readValue(browser.call("/api/auth/consent").body(), Map.class).get("version");

        HttpResponse<String> created = browser.postJson("/api/auth/register", signup(email, version));

        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        Map<String, Object> row = jdbc.queryForMap("select p.consent_version, p.consented_at from patients p "
                + "join users u on u.id = p.id where u.email = ?", email);
        assertThat(row.get("consent_version")).isEqualTo(version);
        assertThat(row.get("consented_at")).isNotNull();
    }

    @Test
    void anOldTextIsRefusedAndNothingIsCreated() throws Exception {
        String email = uniqueEmail("consent-old");

        HttpResponse<String> response = new Browser().postJson("/api/auth/register", signup(email, "2020-01-v0"));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(response.body()).contains("consentVersion");
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isZero();
    }

    /** Consent is required in every profile (no account without it): a missing or blank version is refused. */
    @Test
    @SuppressWarnings("unchecked")
    void aSignupWithoutConsentIsRefusedAndNothingIsCreated() throws Exception {
        Browser browser = new Browser();
        assertThat(mapper.readValue(browser.call("/api/auth/consent").body(), Map.class))
                .containsEntry("required", true);
        String missing = uniqueEmail("consent-missing");
        String blank = uniqueEmail("consent-blank");

        HttpResponse<String> withoutField = browser.postJson("/api/auth/register", """
                {"email":"%s","password":"%s","firstName":"Amel","lastName":"Ben Salah","birthDate":"1990-04-12"}
                """.formatted(missing, PASSWORD));
        HttpResponse<String> blankField = browser.postJson("/api/auth/register", signup(blank, "   "));

        for (HttpResponse<String> refused : List.of(withoutField, blankField)) {
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
            assertThat((Map<String, Object>) mapper.readValue(refused.body(), Map.class).get("details"))
                    .containsKey("consentVersion");
        }
        for (String email : List.of(missing, blank)) {
            assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isZero();
            assertThat(console.read("/users?email={email}&exact=true", email)).asList().isEmpty();
        }
    }

    /** The version and time recorded at sign-up are kept: a contact or preference change never rewrites them. */
    @Test
    @SuppressWarnings("unchecked")
    void theRecordedConsentSurvivesProfileAndPreferenceChanges() throws Exception {
        String email = uniqueEmail("consent-kept");
        Browser browser = new Browser();
        assertThat(browser.postJson("/api/auth/register", signup(email, ConsentPolicy.CURRENT_VERSION)).statusCode())
                .isEqualTo(201);
        Map<String, Object> before = jdbc.queryForMap("select p.id, p.consent_version, p.consented_at from patients p "
                + "join users u on u.id = p.id where u.email = ?", email);
        assertThat(browser.postJson("/api/auth/verify-email",
                "{\"token\":\"" + new Mailbox().verificationToken(email) + "\"}").statusCode()).isEqualTo(204);
        Browser patient = new Browser();
        assertThat(patient.apiLogin(email, PASSWORD).statusCode()).isEqualTo(200);

        assertThat(patient.putJson("/api/patients/me", "{\"phone\": \"+216 20 000 000\", \"address\": \"Sfax\"}")
                .statusCode()).isEqualTo(200);
        assertThat(patient.putJson("/api/patients/me/notification-preferences",
                "{\"appointmentReminders\": false, \"examResults\": false}").statusCode()).isEqualTo(200);

        Map<String, Object> after = jdbc.queryForMap("select consent_version, consented_at from patients where id = ?",
                before.get("id"));
        assertThat(after.get("consent_version")).isEqualTo(before.get("consent_version"));
        assertThat(after.get("consented_at")).isEqualTo(before.get("consented_at"));
    }

    private static String signup(String email, String consentVersion) {
        return """
                {"email":"%s","password":"%s","firstName":"Amel","lastName":"Ben Salah","birthDate":"1990-04-12",
                 "consentVersion":"%s"}
                """.formatted(email, PASSWORD, consentVersion);
    }
}
