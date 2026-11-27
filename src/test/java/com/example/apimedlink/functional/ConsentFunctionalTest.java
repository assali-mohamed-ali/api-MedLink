package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * US-04: the consent text is public; a sign-up that accepts the current text records its version and the time with the
 * patient; an old or unknown version is refused (400) and creates nothing. (The "required in production" rule is
 * covered by ConsentPolicyTest: the functional context runs with the dev default, consent optional.) NOT RUN in the
 * session that wrote it (no Docker).
 */
class ConsentFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Consent-Pass-2026!";

    final JsonMapper mapper = JsonMapper.builder().build();

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

    private static String signup(String email, String consentVersion) {
        return """
                {"email":"%s","password":"%s","firstName":"Amel","lastName":"Ben Salah","birthDate":"1990-04-12",
                 "consentVersion":"%s"}
                """.formatted(email, PASSWORD, consentVersion);
    }
}
