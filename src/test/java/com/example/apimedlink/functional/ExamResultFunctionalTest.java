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

    static final String EXAM_DATE = java.time.LocalDate.now().minusDays(3).toString();

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
                result("Bilan sanguin", "Analyse de sang", "Glycémie normale.", null));

        assertThat(published.statusCode()).as(published.body()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select title_encrypted || type_encrypted || content_encrypted from exam_results "
                + "where patient_id = ?", String.class, patient))
                .doesNotContain("Bilan").doesNotContain("Analyse").doesNotContain("Glycémie");
        Browser browser = new Browser();
        assertThat(browser.apiLogin(email, PASSWORD).statusCode()).isEqualTo(200);
        HttpResponse<String> mine = browser.call("/api/results/me");
        assertThat(mine.statusCode()).as(mine.body()).isEqualTo(200);
        List<Map<String, Object>> results = mapper.readValue(mine.body(), List.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0)).containsEntry("title", "Bilan sanguin").containsEntry("examType", "Analyse de sang")
                .containsEntry("content", "Glycémie normale.").containsEntry("examDate", EXAM_DATE)
                .containsEntry("isNew", true);
        assertThat(results.get(0).get("practitioner")).isEqualTo(Map.of("firstName", "Leila", "lastName", "Ben Ali"));
        List<Map<String, Object>> again = mapper.readValue(browser.call("/api/results/me").body(), List.class);
        assertThat(again.get(0)).as("seen once, no longer new").containsEntry("isNew", false);
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
                result("x", "y", "z", null)).statusCode()).isEqualTo(403);
        assertThat(attached.browser().postJson("/api/patients/" + patient + "/results",
                "{\"title\":\"\",\"examType\":\"\",\"content\":\"\"}").statusCode()).isEqualTo(400);
        assertThat(attached.browser().postJson("/api/patients/" + patient + "/results",
                "{\"title\":\"x\",\"examType\":\"y\",\"content\":\"z\",\"examDate\":\"2999-01-01\"}")
                .statusCode()).as("a future examination date").isEqualTo(400);
        assertThat(stranger.browser().call("/api/patients/" + patient + "/results").statusCode())
                .as("nor can a stranger list them").isEqualTo(403);
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

        assertThat(patientBrowser.postJson("/api/patients/" + patient + "/results", result("x", "y", "z", null))
                .statusCode()).isEqualTo(403);
        assertThat(pro.browser().call("/api/results/me").statusCode()).isEqualTo(403);
        assertThat(new Browser().login(staffEmail, PASSWORD).call("/api/results/me").statusCode()).isEqualTo(403);
        assertThat(new Browser().call("/api/results/me").statusCode()).isEqualTo(401);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aResultCanPointAtOneOfThePatientsDocumentsNeverAnotherPatientsAndThePractitionerListsWhatTheyPublished()
            throws Exception {
        Pro pro = practitioner("Hela", "Docs");
        UUID patient = UUID.randomUUID();
        insertPatient(patient, "patient-" + patient + "@example.com");
        UUID other = UUID.randomUUID();
        insertPatient(other, "patient-" + other + "@example.com");
        appointment(pro.id(), patient);
        UUID mine = document(patient);
        UUID theirs = document(other);

        assertThat(pro.browser().postJson("/api/patients/" + patient + "/results", result("IRM", "Imagerie", "RAS.", theirs))
                .statusCode()).as("another patient's document").isEqualTo(400);
        HttpResponse<String> published = pro.browser().postJson("/api/patients/" + patient + "/results",
                result("IRM", "Imagerie", "RAS.", mine));
        assertThat(published.statusCode()).as(published.body()).isEqualTo(201);

        HttpResponse<String> list = pro.browser().call("/api/patients/" + patient + "/results");
        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
        List<Map<String, Object>> rows = mapper.readValue(list.body(), List.class);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("title", "IRM").containsEntry("documentId", mine.toString())
                .containsEntry("isNew", false);
    }

    private static String result(String title, String type, String content, UUID documentId) {
        return "{\"title\":\"" + title + "\",\"examType\":\"" + type + "\",\"examDate\":\"" + EXAM_DATE
                + "\",\"content\":\"" + content + "\"" + (documentId == null ? "" : ",\"documentId\":\"" + documentId + "\"")
                + "}";
    }

    /** Only the metadata row: the result points at it, nothing downloads it here. */
    private UUID document(UUID patient) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into patient_documents (id, patient_id, title, original_filename, content_type, size_bytes, "
                + "sha256, storage_key, key_version) values (?, ?, 'Compte-rendu IRM', 'irm.pdf', 'application/pdf', 10, ?, ?, 1)",
                id, patient, "a".repeat(64), "test/" + id);
        return id;
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
