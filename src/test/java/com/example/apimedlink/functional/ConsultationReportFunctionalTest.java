package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

    /**
     * 32.7, criterion 1, further: the same text written twice is sealed twice differently (a new IV each time), and the
     * column holds neither the text nor its plain base64, so nothing in the database can be matched to a known text.
     */
    @Test
    void theSameTextIsSealedDifferentlyEachTimeAndNeverAsItsPlainBase64() throws Exception {
        Pro pro = practitioner();
        UUID patient = insertPatient();
        appointment(pro.id(), patient, "BOOKED");

        pro.browser().postJson(reports(patient), json(TEXT));
        pro.browser().postJson(reports(patient), json(TEXT));

        List<String> sealed = jdbc.queryForList("select content_encrypted from consultation_reports where patient_id = ?",
                String.class, patient);
        String plainBase64 = java.util.Base64.getEncoder().encodeToString(TEXT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(sealed).hasSize(2).doesNotHaveDuplicates();
        assertThat(sealed).allSatisfy(stored -> assertThat(stored).doesNotContain(TEXT).doesNotContain(plainBase64)
                .doesNotContain(plainBase64.substring(0, 16)));
        // Both still read back as written
        assertThat(list(pro.browser().call(reports(patient))).stream().map(item -> map(item).get("content")).toList())
                .containsExactly(TEXT, TEXT);
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

    // ---- Review on #123: what a write must pass before its body is read ----

    /**
     * Not attached: the record's 403 whatever the body is, so before Spring reads it. A malformed body would be a
     * JSON-parser 400 and a huge one would be read in full if the attachment were checked in the service.
     */
    @Test
    void aPractitionerWhoIsNotAttachedGets403BeforeTheBodyIsReadWhateverItHolds() throws Exception {
        Pro attached = practitioner();
        Pro other = practitioner();
        UUID patient = insertPatient();
        appointment(attached.id(), patient, "BOOKED");

        List<HttpResponse<String>> refused = List.of(
                other.browser().postJson(reports(patient), "{not json"),
                other.browser().postJson(reports(patient), json("x".repeat(100_000))),
                postUnsized(other.browser(), reports(patient), json(TEXT)));

        for (HttpResponse<String> response : refused) {
            assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
            assertThat(Browser.json(response).get("code")).isEqualTo("FORBIDDEN");
        }
        assertThat(jdbc.queryForObject("select count(*) from consultation_reports where patient_id = ?", Integer.class, patient))
                .isZero();
    }

    /**
     * Attached: a body over the hard byte limit is 413 and one that does not say its size (chunked) is 411, both
     * before it is read; nothing is saved. A malformed body is still the usual 400.
     */
    @Test
    void anAttachedPractitionersOversizedOrUnsizedBodyIsRefusedBeforeItIsRead() throws Exception {
        Pro pro = practitioner();
        UUID patient = insertPatient();
        appointment(pro.id(), patient, "BOOKED");

        HttpResponse<String> oversized = pro.browser().postJson(reports(patient), json("x".repeat(100_000)));
        HttpResponse<String> unsized = postUnsized(pro.browser(), reports(patient), json(TEXT));
        HttpResponse<String> malformed = pro.browser().postJson(reports(patient), "{not json");

        assertThat(oversized.statusCode()).as(oversized.body()).isEqualTo(413);
        assertThat(Browser.json(oversized).get("code")).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(unsized.statusCode()).as(unsized.body()).isEqualTo(411);
        assertThat(Browser.json(unsized).get("code")).isEqualTo("LENGTH_REQUIRED");
        assertThat(malformed.statusCode()).as(malformed.body()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from consultation_reports where patient_id = ?", Integer.class, patient))
                .isZero();
    }

    /** The byte limit never refuses a valid report: 10 000 characters, each at its longest JSON escape, still pass. */
    @Test
    void theLongestValidReportStillFitsUnderTheByteLimit() throws Exception {
        Pro pro = practitioner();
        UUID patient = insertPatient();
        appointment(pro.id(), patient, "BOOKED");

        String body = "{\"content\": \"" + "\\u00e9".repeat(10_000) + "\"}";

        HttpResponse<String> created = pro.browser().postJson(reports(patient), body);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat((String) Browser.json(created).get("content")).hasSize(10_000);
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

    /**
     * A JSON post whose body does not say its size (no Content-Length: HTTP/1.1 chunked), with the browser's session
     * and CSRF header, as a client streaming a body would send it.
     */
    private static HttpResponse<String> postUnsized(Browser browser, String path, String json) throws Exception {
        if (browser.cookies.value("XSRF-TOKEN") == null) {
            browser.call("/api/auth/csrf");
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(appUrl(path)))
                .version(HttpClient.Version.HTTP_1_1)
                .header("X-Forwarded-For", browser.forwardedFor)
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", browser.cookies.value("XSRF-TOKEN"))
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)))
                .build();
        return browser.http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String reports(UUID patient) {
        return "/api/patients/" + patient + "/reports";
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
