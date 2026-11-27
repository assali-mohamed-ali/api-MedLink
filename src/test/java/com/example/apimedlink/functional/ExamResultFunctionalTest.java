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
 * US-25 / US-13: a practitioner publishes an examination result for an attached patient; the patient reads it with who
 * published it; the database never holds the text in clear; a practitioner not attached gets 403; roles are checked.
 * NOT RUN in the session that wrote it (no Docker).
 */
class ExamResultFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Exam-Result-2026!";
    static final Instant PAST = Instant.now().truncatedTo(ChronoUnit.DAYS).minus(2, ChronoUnit.DAYS).plus(9, ChronoUnit.HOURS);

    final KeycloakConsole console = new KeycloakConsole();
    final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    JdbcTemplate jdbc;

    record Pro(UUID id, Browser browser) {}

    @Test
    @SuppressWarnings("unchecked")
    void aPractitionerPublishesAResultThePatientReadsItAndTheDatabaseHoldsNoClearText() throws Exception {
        Pro pro = practitioner("Leila", "Ben Ali");
        String email = email("result-patient");
        UUID patient = console.createUser(email, PASSWORD);
        insertPatient(patient, email);
        appointment(pro.id(), patient);

        HttpResponse<String> published = pro.browser().postJson("/api/patients/" + patient + "/results",
                "{\"title\":\"Bilan sanguin\",\"content\":\"Glycémie normale.\"}");

        assertThat(published.statusCode()).as(published.body()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select title_encrypted || content_encrypted from exam_results where patient_id = ?",
                String.class, patient)).doesNotContain("Bilan").doesNotContain("Glycémie");
        Browser browser = new Browser();
        assertThat(browser.apiLogin(email, PASSWORD).statusCode()).isEqualTo(200);
        HttpResponse<String> mine = browser.call("/api/results/me");
        assertThat(mine.statusCode()).as(mine.body()).isEqualTo(200);
        List<Map<String, Object>> results = mapper.readValue(mine.body(), List.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0)).containsEntry("title", "Bilan sanguin").containsEntry("content", "Glycémie normale.");
        assertThat(results.get(0).get("practitioner")).isEqualTo(Map.of("firstName", "Leila", "lastName", "Ben Ali"));
        assertThat(jdbc.queryForObject("select count(*) from audit_entries where action = 'REPORT_CREATED' "
                + "and resource_type = 'EXAM_RESULT'", Integer.class)).isPositive();
    }

    @Test
    void aPractitionerNotAttachedIsRefusedAndAnEmptyResultIs400() throws Exception {
        Pro attached = practitioner("Sami", "Lie");
        Pro stranger = practitioner("Nadia", "Etrangere");
        UUID patient = UUID.randomUUID();
        insertPatient(patient, "patient-" + patient + "@example.com");
        appointment(attached.id(), patient);

        assertThat(stranger.browser().postJson("/api/patients/" + patient + "/results",
                "{\"title\":\"x\",\"content\":\"y\"}").statusCode()).isEqualTo(403);
        assertThat(attached.browser().postJson("/api/patients/" + patient + "/results",
                "{\"title\":\"\",\"content\":\"\"}").statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from exam_results where patient_id = ?", Integer.class, patient))
                .isZero();
    }

    @Test
    void patientsCannotPublishAndPractitionersOrStaffCannotReadAPatientsOwnList() throws Exception {
        Pro pro = practitioner("Omar", "Lecteur");
        String patientEmail = email("result-patient2");
        UUID patient = console.createUser(patientEmail, PASSWORD);
        insertPatient(patient, patientEmail);
        Browser patientBrowser = new Browser();
        assertThat(patientBrowser.apiLogin(patientEmail, PASSWORD).statusCode()).isEqualTo(200);
        String staffEmail = email("result-staff");
        console.createUser(staffEmail, PASSWORD, "/administrative-staff");

        assertThat(patientBrowser.postJson("/api/patients/" + patient + "/results", "{\"title\":\"x\",\"content\":\"y\"}")
                .statusCode()).isEqualTo(403);
        assertThat(pro.browser().call("/api/results/me").statusCode()).isEqualTo(403);
        assertThat(new Browser().login(staffEmail, PASSWORD).call("/api/results/me").statusCode()).isEqualTo(403);
        assertThat(new Browser().call("/api/results/me").statusCode()).isEqualTo(401);
    }

    private Pro practitioner(String firstName, String lastName) throws Exception {
        String email = email("result-pro");
        UUID id = console.createUser(email, PASSWORD, "/practitioners");
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?) on conflict (id) do nothing", id, email, email);
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, ?, ?, ?)",
                id, firstName, lastName, specialty);
        return new Pro(id, new Browser().login(email, PASSWORD));
    }

    private void insertPatient(UUID id, String email) {
        jdbc.update("insert into users (id, username, email) values (?, ?, ?) on conflict (id) do nothing", id, email, email);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Ben Salah', ?)",
                id, Date.valueOf("1990-04-12"));
    }

    private void appointment(UUID practitioner, UUID patient) {
        UUID clinic = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, ?, 'Sousse')", clinic, "Clinique " + clinic);
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", practitioner, clinic);
        UUID slot = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, 'BOOKED')",
                slot, practitioner, clinic, Timestamp.from(PAST), Timestamp.from(PAST.plus(30, ChronoUnit.MINUTES)));
        jdbc.update("insert into appointments (id, slot_id, patient_id, booked_by, status) values (?, ?, ?, ?, 'BOOKED')",
                UUID.randomUUID(), slot, patient, patient);
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
