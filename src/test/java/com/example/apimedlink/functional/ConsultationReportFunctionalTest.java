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
 * MLink-32 (32.3): a practitioner writes and reads the consultation reports of a patient attached to them, through the
 * real API. "Attached" is the record's rule (MLink-31): a BOOKED appointment with the patient. Anyone else gets the
 * record's 403. The content is sealed in the database (32.2) and comes back as written. Written first (red).
 */
class ConsultationReportFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Consultation-Report-2026!";

    static final Instant DAY = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(4, ChronoUnit.DAYS).plus(9, ChronoUnit.HOURS);

    static final String TEXT = "Douleur thoracique atypique, ECG normal. Contrôle dans un mois.";

    final KeycloakConsole console = new KeycloakConsole();
    final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    JdbcTemplate jdbc;

    record Pro(UUID id, Browser browser) {}

    @Test
    void anAttachedPractitionerWritesAReportAndReadsItBackAsWritten() throws Exception {
        Pro pro = practitioner();
        UUID patient = insertPatient();
        appointment(pro.id(), patient, "BOOKED");

        HttpResponse<String> created = pro.browser().postJson(reports(patient), json(TEXT));

        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        Map<String, Object> report = map(Browser.json(created));
        assertThat(report).containsOnlyKeys("id", "createdAt", "content");
        assertThat(report.get("content")).isEqualTo(TEXT);
        Instant createdAt = Instant.parse((String) report.get("createdAt"));
        assertThat(createdAt).isBetween(Instant.now().minus(1, ChronoUnit.MINUTES), Instant.now().plus(1, ChronoUnit.MINUTES));

        HttpResponse<String> list = pro.browser().call(reports(patient));
        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
        assertThat(list(list)).containsExactly((Object) report);
        assertThat(list.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }

    @Test
    void theContentIsSealedInTheDatabaseNeverInPlainText() throws Exception {
        Pro pro = practitioner();
        UUID patient = insertPatient();
        appointment(pro.id(), patient, "BOOKED");

        String id = (String) Browser.json(pro.browser().postJson(reports(patient), json(TEXT))).get("id");

        Map<String, Object> row = jdbc.queryForMap("select patient_id, practitioner_id, content_encrypted "
                + "from consultation_reports where id = ?", UUID.fromString(id));
        assertThat(row.get("patient_id")).isEqualTo(patient);
        assertThat(row.get("practitioner_id")).isEqualTo(pro.id());
        assertThat((String) row.get("content_encrypted")).doesNotContain("Douleur").doesNotContain("ECG");
    }

    @Test
    void theReportsComeNewestFirstAndEveryAttachedPractitionerSeesThePatientsReports() throws Exception {
        Pro first = practitioner();
        Pro second = practitioner();
        UUID patient = insertPatient();
        appointment(first.id(), patient, "BOOKED");
        appointment(second.id(), patient, "BOOKED");

        first.browser().postJson(reports(patient), json("Premier compte-rendu."));
        second.browser().postJson(reports(patient), json("Deuxième compte-rendu."));

        List<Object> list = list(first.browser().call(reports(patient)));
        assertThat(list.stream().map(item -> map(item).get("content")).toList())
                .containsExactly("Deuxième compte-rendu.", "Premier compte-rendu.");
    }

    @Test
    void anEmptyOrTooLongReportIsRefusedUnderItsFieldAndNothingIsSaved() throws Exception {
        Pro pro = practitioner();
        UUID patient = insertPatient();
        appointment(pro.id(), patient, "BOOKED");

        HttpResponse<String> blank = pro.browser().postJson(reports(patient), json("   "));
        HttpResponse<String> missing = pro.browser().postJson(reports(patient), "{}");
        HttpResponse<String> tooLong = pro.browser().postJson(reports(patient), json("x".repeat(10_001)));

        for (HttpResponse<String> refused : List.of(blank, missing, tooLong)) {
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
            assertThat(Browser.json(refused).get("code")).isEqualTo("VALIDATION_FAILED");
            assertThat(map(Browser.json(refused).get("details"))).containsOnlyKeys("content");
        }
        assertThat(((Map<?, ?>) Browser.json(tooLong).get("details")).get("content")).asString().contains("10 000");
        assertThat(pro.browser().postJson(reports(patient), json("x".repeat(10_000))).statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select count(*) from consultation_reports where patient_id = ?", Integer.class, patient))
                .isEqualTo(1);
    }

    @Test
    void aPractitionerWhoIsNotAttachedGetsTheRecordsSame403AndNothingIsSaved() throws Exception {
        Pro attached = practitioner();
        Pro other = practitioner();
        UUID patient = insertPatient();
        appointment(attached.id(), patient, "BOOKED");
        UUID cancelledOnly = insertPatient();
        appointment(other.id(), cancelledOnly, "CANCELLED");

        List<HttpResponse<String>> refused = List.of(
                other.browser().postJson(reports(patient), json(TEXT)),
                other.browser().call(reports(patient)),
                other.browser().postJson(reports(cancelledOnly), json(TEXT)),
                other.browser().postJson(reports(UUID.randomUUID()), json(TEXT)),
                other.browser().postJson("/api/patients/not-a-uuid/reports", json(TEXT)),
                // Not attached: refused before the body is even looked at
                other.browser().postJson(reports(patient), "{}"));

        for (HttpResponse<String> response : refused) {
            assertThat(response.statusCode()).as(response.uri() + " " + response.body()).isEqualTo(403);
        }
        assertThat(jdbc.queryForObject("select count(*) from consultation_reports where practitioner_id = ?", Integer.class,
                other.id())).isZero();
    }

    @Test
    void patientsStaffAndAnonymousCallersAreRefused() throws Exception {
        Pro pro = practitioner();
        String patientEmail = email("report-patient");
        UUID patientId = console.createUser(patientEmail, PASSWORD);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?) on conflict (id) do nothing",
                patientId, patientEmail, patientEmail);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Self', ?)",
                patientId, Date.valueOf("1990-04-12"));
        appointment(pro.id(), patientId, "BOOKED");
        Browser patient = new Browser();
        assertThat(patient.apiLogin(patientEmail, PASSWORD).statusCode()).isEqualTo(200);
        String staffEmail = email("report-staff");
        console.createUser(staffEmail, PASSWORD, "/administrative-staff");
        Browser staff = new Browser().login(staffEmail, PASSWORD);

        // Even the patient concerned, even with a bad body: 403, never 400
        assertThat(patient.call(reports(patientId)).statusCode()).isEqualTo(403);
        assertThat(patient.postJson(reports(patientId), json(TEXT)).statusCode()).isEqualTo(403);
        assertThat(patient.postJson(reports(patientId), "{not json").statusCode()).isEqualTo(403);
        assertThat(staff.postJson(reports(patientId), json(TEXT)).statusCode()).isEqualTo(403);
        assertThat(new Browser().call(reports(patientId)).statusCode()).isEqualTo(401);
    }

    @SuppressWarnings("unchecked")
    // ---- 32.4: every write and every read is traced, ids only ----

    @Test
    void everyWriteAddsOneReportCreatedEntryWithTheIdsOnly() throws Exception {
        Pro pro = practitioner();
        UUID patient = insertPatient();
        appointment(pro.id(), patient, "BOOKED");

        String id = (String) Browser.json(pro.browser().postJson(reports(patient), json(TEXT))).get("id");

        List<Map<String, Object>> rows = jdbc.queryForList("select user_id, resource_type, resource_id, outcome "
                + "from audit_entries where action = 'REPORT_CREATED' and user_id = ?", pro.id());
        assertThat(rows).containsExactly(Map.of("user_id", pro.id(), "resource_type", "CONSULTATION_REPORT",
                "resource_id", id, "outcome", "SUCCESS"));
        // Ids only: never the text
        assertThat(jdbc.queryForObject("select count(*) from audit_entries where resource_id like '%Douleur%'", Integer.class))
                .isZero();
    }

    @Test
    void everyReadAddsOneReportViewedEntryForThePatient() throws Exception {
        Pro pro = practitioner();
        UUID patient = insertPatient();
        appointment(pro.id(), patient, "BOOKED");

        assertThat(pro.browser().call(reports(patient)).statusCode()).isEqualTo(200);
        assertThat(pro.browser().call(reports(patient)).statusCode()).isEqualTo(200);

        List<Map<String, Object>> rows = jdbc.queryForList("select resource_type, resource_id, outcome from audit_entries "
                + "where action = 'REPORT_VIEWED' and user_id = ?", pro.id());
        assertThat(rows).hasSize(2).allSatisfy(row -> assertThat(row).isEqualTo(Map.of(
                "resource_type", "PATIENT_REPORTS", "resource_id", patient.toString(), "outcome", "SUCCESS")));
    }

    @Test
    void aRefusedOrInvalidCallIsNeverTracedAsAWriteOrARead() throws Exception {
        Pro attached = practitioner();
        Pro other = practitioner();
        UUID patient = insertPatient();
        appointment(attached.id(), patient, "BOOKED");

        other.browser().postJson(reports(patient), json(TEXT));
        other.browser().call(reports(patient));
        attached.browser().postJson(reports(patient), json("   "));

        assertThat(jdbc.queryForObject("select count(*) from audit_entries where action in ('REPORT_CREATED', 'REPORT_VIEWED') "
                + "and user_id in (?, ?)", Integer.class, attached.id(), other.id())).isZero();
        // The refusals are traced as such
        assertThat(jdbc.queryForObject("select count(*) from audit_entries where action = 'ACCESS_DENIED' and user_id = ?",
                Integer.class, other.id())).isEqualTo(2);
    }

    private List<Object> list(HttpResponse<String> response) {
        return mapper.readValue(response.body(), List.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object json) {
        return (Map<String, Object>) json;
    }

    private static String json(String content) {
        return "{\"content\": \"" + content.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    private Pro practitioner() throws Exception {
        String email = email("report-pro");
        UUID id = console.createUser(email, PASSWORD, "/practitioners");
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?) on conflict (id) do nothing", id, email, email);
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Sami', 'Dev', ?)", id, specialty);
        return new Pro(id, new Browser().login(email, PASSWORD));
    }

    private UUID insertPatient() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", id, "patient-" + id, "patient-" + id + "@example.com");
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Ben Salah', ?)",
                id, Date.valueOf("1990-04-12"));
        return id;
    }

    /** One clinic of the practitioner's, one slot, one appointment of the patient on it. */
    private void appointment(UUID practitioner, UUID patient, String status) {
        UUID clinic = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, ?, 'Sousse')", clinic, "Clinique " + clinic);
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", practitioner, clinic);
        UUID slot = UUID.randomUUID();
        Instant start = DAY.plus((long) (Math.random() * 1000), ChronoUnit.MINUTES);
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, ?)",
                slot, practitioner, clinic, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)),
                "BOOKED".equals(status) ? "BOOKED" : "FREE");
        jdbc.update("insert into appointments (id, slot_id, patient_id, booked_by, status) values (?, ?, ?, ?, ?)",
                UUID.randomUUID(), slot, patient, patient, status);
    }

    private static String reports(UUID patient) {
        return "/api/patients/" + patient + "/reports";
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
