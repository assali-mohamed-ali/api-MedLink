package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * US-15: a patient reads and edits their own phone and address, {@code GET/PUT /api/patients/me}, through the real
 * API. Only phone and address change: names, birth date and email stay as they are, and nothing in the body can
 * point at another account. Staff and practitioners get 403, anonymous callers 401. NOT RUN in the session that
 * wrote it (no Docker): run it before merging.
 */
class PatientProfileFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Patient-Profile-2026!";

    final KeycloakConsole console = new KeycloakConsole();
    final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    JdbcTemplate jdbc;

    record Patient(UUID id, Browser browser) {}

    @Test
    void aPatientReadsTheirOwnPhoneAndAddress() throws Exception {
        Patient patient = patient("+216 20 123 456", "12 rue de Carthage, Tunis");

        HttpResponse<String> response = patient.browser().call("/api/patients/me");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(map(response)).containsOnly(
                Map.entry("phone", "+216 20 123 456"), Map.entry("address", "12 rue de Carthage, Tunis"));
    }

    @Test
    void aPatientUpdatesPhoneAndAddressAndTheChangeIsAudited() throws Exception {
        Patient patient = patient(null, null);

        HttpResponse<String> response = patient.browser().putJson("/api/patients/me",
                "{\"phone\": \" +216 98 765 432 \", \"address\": \" 5 avenue Habib Bourguiba \"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(map(response)).containsOnly(
                Map.entry("phone", "+216 98 765 432"), Map.entry("address", "5 avenue Habib Bourguiba"));
        assertThat(map(patient.browser().call("/api/patients/me"))).containsEntry("phone", "+216 98 765 432");
        Map<String, Object> row = jdbc.queryForMap("select phone, address, first_name, last_name, birth_date "
                + "from patients where id = ?", patient.id());
        assertThat(row).containsEntry("phone", "+216 98 765 432").containsEntry("first_name", "Amel")
                .containsEntry("last_name", "Ben Salah");
        assertThat(jdbc.queryForList("select outcome, resource_id from audit_entries "
                + "where action = 'ACCOUNT_UPDATED' and resource_type = 'PATIENT' and user_id = ?", patient.id()))
                .containsExactly(Map.of("outcome", "SUCCESS", "resource_id", patient.id().toString()));
    }

    @Test
    void aBlankValueClearsTheField() throws Exception {
        Patient patient = patient("+216 20 123 456", "12 rue de Carthage");

        HttpResponse<String> response = patient.browser().putJson("/api/patients/me", "{\"phone\": \"  \", \"address\": \"\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        Map<String, Object> row = jdbc.queryForMap("select phone, address from patients where id = ?", patient.id());
        assertThat(row.get("phone")).isNull();
        assertThat(row.get("address")).isNull();
    }

    @Test
    void anInvalidPhoneOrTooLongAddressIsRefusedAndNothingChanges() throws Exception {
        Patient patient = patient("+216 20 123 456", "12 rue de Carthage");

        assertThat(patient.browser().putJson("/api/patients/me", "{\"phone\": \"abc\", \"address\": \"x\"}").statusCode())
                .isEqualTo(400);
        assertThat(patient.browser().putJson("/api/patients/me",
                "{\"phone\": \"1\", \"address\": \"" + "a".repeat(256) + "\"}").statusCode()).isEqualTo(400);

        assertThat(jdbc.queryForMap("select phone, address from patients where id = ?", patient.id()))
                .containsEntry("phone", "+216 20 123 456").containsEntry("address", "12 rue de Carthage");
    }

    /** Never the role, the id, the email or the names from a body: unknown fields change nothing. */
    @Test
    void aBodyCannotChangeAnythingButPhoneAndAddress() throws Exception {
        Patient patient = patient(null, null);
        UUID victim = insertPatient();

        HttpResponse<String> response = patient.browser().putJson("/api/patients/me", "{\"phone\": \"+216 11 111 111\", "
                + "\"id\": \"" + victim + "\", \"role\": \"ADMINISTRATIVE_STAFF\", \"firstName\": \"Hacker\", "
                + "\"email\": \"hacker@example.com\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select phone from patients where id = ?", String.class, victim)).isNull();
        assertThat(jdbc.queryForObject("select first_name from patients where id = ?", String.class, patient.id()))
                .isEqualTo("Amel");
        assertThat(jdbc.queryForObject("select phone from patients where id = ?", String.class, patient.id()))
                .isEqualTo("+216 11 111 111");
    }

    @Test
    void aWriteWithoutTheCsrfHeaderIsRefused() throws Exception {
        Patient patient = patient(null, null);

        HttpResponse<String> response = patient.browser().post("/api/patients/me", "{}", false);

        assertThat(response.statusCode()).isIn(401, 403, 405);
        assertThat(jdbc.queryForObject("select phone from patients where id = ?", String.class, patient.id())).isNull();
    }

    @Test
    void staffPractitionersAndAnonymousCallersAreRefused() throws Exception {
        String staffEmail = email("profile-staff");
        console.createUser(staffEmail, PASSWORD, "/administrative-staff");
        Browser staff = new Browser().login(staffEmail, PASSWORD);
        String proEmail = email("profile-pro");
        console.createUser(proEmail, PASSWORD, "/practitioners");
        Browser pro = new Browser().login(proEmail, PASSWORD);

        for (Browser other : List.of(staff, pro)) {
            assertThat(other.call("/api/patients/me").statusCode()).isEqualTo(403);
            assertThat(other.putJson("/api/patients/me", "{\"phone\": \"1\"}").statusCode()).isEqualTo(403);
        }
        assertThat(new Browser().call("/api/patients/me").statusCode()).isEqualTo(401);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(HttpResponse<String> response) {
        return mapper.readValue(response.body(), Map.class);
    }

    private Patient patient(String phone, String address) throws Exception {
        String email = email("profile-patient");
        UUID id = console.createUser(email, PASSWORD);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?) on conflict (id) do nothing", id, email, email);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date, phone, address) "
                + "values (?, 'Amel', 'Ben Salah', ?, ?, ?)", id, Date.valueOf("1990-04-12"), phone, address);
        Browser browser = new Browser();
        assertThat(browser.apiLogin(email, PASSWORD).statusCode()).isEqualTo(200);
        return new Patient(id, browser);
    }

    /** Someone else's patient row, with no account. */
    private UUID insertPatient() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", id, "patient-" + id, "patient-" + id + "@example.com");
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Other', 'Patient', ?)",
                id, Date.valueOf("1985-01-01"));
        return id;
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
