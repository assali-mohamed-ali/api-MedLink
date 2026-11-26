package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * MLink-32 follow-up: the patient reads the consultation reports written about them, {@code GET /api/reports/me},
 * through the real API: newest first, with the practitioner who wrote each one (name and specialty), never another
 * patient's. The caller is always the patient: no id in the URL. Practitioners and staff get 403, anonymous 401.
 * Written first (red).
 */
class MyReportsFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "My-Reports-Patient-2026!";

    /** Two days ago: the consultations have started, so their reports can be written. */
    static final Instant PAST = Instant.now().truncatedTo(ChronoUnit.DAYS).minus(2, ChronoUnit.DAYS).plus(9, ChronoUnit.HOURS);

    final KeycloakConsole console = new KeycloakConsole();
    final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    JdbcTemplate jdbc;

    record Pro(UUID id, Browser browser, String specialty) {}

    record Patient(UUID id, Browser browser) {}

    @Test
    void aPatientReadsTheirReportsNewestFirstWithWhoWroteThemAndNobodyElses() throws Exception {
        Pro first = practitioner("Leila", "Ben Ali");
        Pro second = practitioner("Nadia", "Haddad");
        Patient patient = patient();
        UUID other = insertPatient();
        appointment(first.id(), patient.id(), PAST);
        appointment(second.id(), patient.id(), PAST.plus(1, ChronoUnit.HOURS));
        appointment(first.id(), other, PAST.plus(2, ChronoUnit.HOURS));
        assertThat(first.browser().postJson(reports(patient.id()), json("Premier compte-rendu.")).statusCode()).isEqualTo(201);
        assertThat(second.browser().postJson(reports(patient.id()), json("Deuxième compte-rendu.")).statusCode()).isEqualTo(201);
        assertThat(first.browser().postJson(reports(other), json("Compte-rendu d'un autre patient.")).statusCode()).isEqualTo(201);

        HttpResponse<String> response = patient.browser().call("/api/reports/me");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        List<Map<String, Object>> mine = list(response);
        assertThat(mine).extracting(report -> report.get("content"))
                .containsExactly("Deuxième compte-rendu.", "Premier compte-rendu.");
        assertThat(mine.get(0)).containsOnlyKeys("id", "createdAt", "content", "practitioner");
        assertThat(mine.get(0).get("practitioner"))
                .isEqualTo(Map.of("firstName", "Nadia", "lastName", "Haddad", "specialty", second.specialty()));
        assertThat(mine.get(1).get("practitioner"))
                .isEqualTo(Map.of("firstName", "Leila", "lastName", "Ben Ali", "specialty", first.specialty()));
        assertThat(response.body()).doesNotContain("autre patient");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }

    @Test
    void aPatientWithoutReportsGetsAnEmptyList() throws Exception {
        Patient patient = patient();

        HttpResponse<String> response = patient.browser().call("/api/reports/me");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(list(response)).isEmpty();
    }

    /** 32.4: the patient's read is traced, ids only, as a practitioner's. */
    @Test
    void everyReadAddsOneReportViewedEntryForThePatient() throws Exception {
        Patient patient = patient();

        assertThat(patient.browser().call("/api/reports/me").statusCode()).isEqualTo(200);

        List<Map<String, Object>> rows = jdbc.queryForList("select resource_type, resource_id, outcome from audit_entries "
                + "where action = 'REPORT_VIEWED' and user_id = ?", patient.id());
        assertThat(rows).containsExactly(Map.of(
                "resource_type", "PATIENT_REPORTS", "resource_id", patient.id().toString(), "outcome", "SUCCESS"));
    }

    @Test
    void practitionersStaffAndAnonymousCallersAreRefused() throws Exception {
        Pro pro = practitioner("Sami", "Dev");
        String staffEmail = email("my-reports-staff");
        console.createUser(staffEmail, PASSWORD, "/administrative-staff");
        Browser staff = new Browser().login(staffEmail, PASSWORD);

        assertThat(pro.browser().call("/api/reports/me").statusCode()).isEqualTo(403);
        assertThat(staff.call("/api/reports/me").statusCode()).isEqualTo(403);
        assertThat(new Browser().call("/api/reports/me").statusCode()).isEqualTo(401);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(HttpResponse<String> response) {
        return mapper.readValue(response.body(), List.class);
    }

    private static String json(String content) {
        return "{\"content\": \"" + content.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    private Pro practitioner(String firstName, String lastName) throws Exception {
        String email = email("my-reports-pro");
        UUID id = console.createUser(email, PASSWORD, "/practitioners");
        UUID specialty = UUID.randomUUID();
        String name = "Specialty " + specialty;
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, name);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?) on conflict (id) do nothing", id, email, email);
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, ?, ?, ?)",
                id, firstName, lastName, specialty);
        return new Pro(id, new Browser().login(email, PASSWORD), name);
    }

    /** A patient with an account, logged in. */
    private Patient patient() throws Exception {
        String email = email("my-reports-patient");
        UUID id = console.createUser(email, PASSWORD);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?) on conflict (id) do nothing", id, email, email);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Ben Salah', ?)",
                id, Date.valueOf("1990-04-12"));
        Browser browser = new Browser();
        assertThat(browser.apiLogin(email, PASSWORD).statusCode()).isEqualTo(200);
        return new Patient(id, browser);
    }

    /** A patient without an account: the reports of someone else. */
    private UUID insertPatient() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", id, "patient-" + id, "patient-" + id + "@example.com");
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Youssef', 'Autre', ?)",
                id, Date.valueOf("1985-02-03"));
        return id;
    }

    /** One clinic of the practitioner's, one slot that has started, one BOOKED appointment of the patient on it. */
    private void appointment(UUID practitioner, UUID patient, Instant start) {
        UUID clinic = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, ?, 'Sousse')", clinic, "Clinique " + clinic);
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", practitioner, clinic);
        UUID slot = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, 'BOOKED')",
                slot, practitioner, clinic, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)));
        jdbc.update("insert into appointments (id, slot_id, patient_id, booked_by, status) values (?, ?, ?, ?, 'BOOKED')",
                UUID.randomUUID(), slot, patient, patient);
    }

    private static String reports(UUID patient) {
        return "/api/patients/" + patient + "/reports";
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
