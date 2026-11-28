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
        List<Map<String, Object>> results = items(mine);
        assertThat(results).hasSize(1);
        assertThat(results.get(0)).containsEntry("title", "Bilan sanguin").containsEntry("examType", "Analyse de sang")
                .containsEntry("content", "Glycémie normale.").containsEntry("examDate", EXAM_DATE)
                .containsEntry("isNew", true);
        assertThat(results.get(0).get("practitioner")).isEqualTo(Map.of("firstName", "Leila", "lastName", "Ben Ali"));
        List<Map<String, Object>> again = items(browser.call("/api/results/me"));
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
        List<Map<String, Object>> rows = items(list);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("title", "IRM").containsEntry("documentId", mine.toString())
                .containsEntry("isNew", false);
    }

    /**
     * US-25: the same request id twice (a double click, a retry) gives the first result back (200), never a second
     * row; two concurrent submissions with one id also leave one row; the id reused for another patient is 409.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aRepeatedPublicationReturnsTheFirstResultAndCreatesNoDuplicate() throws Exception {
        Pro pro = practitioner("Rania", "Unique");
        UUID patient = UUID.randomUUID();
        insertPatient(patient, "patient-" + patient + "@example.com");
        UUID other = UUID.randomUUID();
        insertPatient(other, "patient-" + other + "@example.com");
        appointment(pro.id(), patient);
        appointment(pro.id(), other, PAST.plus(1, ChronoUnit.HOURS));
        UUID requestId = UUID.randomUUID();
        String body = withRequestId(result("Bilan", "Biologie", "RAS.", null), requestId);

        HttpResponse<String> first = pro.browser().postJson("/api/patients/" + patient + "/results", body);
        HttpResponse<String> again = pro.browser().postJson("/api/patients/" + patient + "/results", body);

        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
        assertThat(mapper.readValue(again.body(), Map.class).get("id"))
                .isEqualTo(mapper.readValue(first.body(), Map.class).get("id"));
        assertThat(pro.browser().postJson("/api/patients/" + other + "/results", body).statusCode())
                .as("the id names another patient's result").isEqualTo(409);

        UUID raced = UUID.randomUUID();
        String racedBody = withRequestId(result("Bilan 2", "Biologie", "RAS.", null), raced);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<Integer>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(pool.submit(() ->
                        pro.browser().postJson("/api/patients/" + patient + "/results", racedBody).statusCode()));
            }
            List<Integer> statuses = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<Integer> call : calls) {
                statuses.add(call.get());
            }
            assertThat(statuses).allMatch(code -> code == 200 || code == 201).contains(201);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from exam_results where patient_id = ?", Integer.class, patient))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from audit_entries where action = 'REPORT_CREATED' "
                + "and resource_type = 'EXAM_RESULT' and resource_id in (select id::text from exam_results "
                + "where patient_id = ?)", Integer.class, patient)).as("one creation audited per result").isEqualTo(2);
    }

    /**
     * US-25: once published, the patient is told by mail, with nothing medical in it, one Message-ID per result; a
     * patient who turned the notice off gets none and the notice is settled all the same.
     */
    @Test
    void thePatientIsNotifiedWithoutMedicalContentUnlessTheyTurnedItOff() throws Exception {
        Pro pro = practitioner("Nabil", "Avis");
        String email = email("result-notice");
        UUID patient = UUID.randomUUID();
        insertPatient(patient, email);
        appointment(pro.id(), patient);
        String quiet = email("result-quiet");
        UUID quietPatient = UUID.randomUUID();
        insertPatient(quietPatient, quiet);
        jdbc.update("update patients set notify_exam_results = false where id = ?", quietPatient);
        appointment(pro.id(), quietPatient, PAST.plus(1, ChronoUnit.HOURS));

        HttpResponse<String> published = pro.browser().postJson("/api/patients/" + patient + "/results",
                result("Scanner thoracique", "Imagerie", "Nodule de 4 mm.", null));
        assertThat(pro.browser().postJson("/api/patients/" + quietPatient + "/results",
                result("Scanner", "Imagerie", "RAS.", null)).statusCode()).isEqualTo(201);

        assertThat(published.statusCode()).as(published.body()).isEqualTo(201);
        String id = (String) mapper.readValue(published.body(), Map.class).get("id");
        Mailbox mailbox = new Mailbox();
        List<Map<?, ?>> mails = mailbox.to(email, "Nouveau résultat dans votre dossier MedLink");
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).get("MessageID")).isEqualTo("exam-result-" + id + "@medlink.local");
        assertThat(mailbox.text(mails.get(0))).contains("/patient/resultats").doesNotContain("Scanner")
                .doesNotContain("Nodule").doesNotContain("Imagerie").doesNotContain("Nabil");
        assertThat(mailbox.to(quiet)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from exam_results where patient_id in (?, ?) "
                + "and notice_sent_at is null", Integer.class, patient, quietPatient)).as("both settled").isZero();
    }

    /**
     * US-25: from the record, an attached practitioner uploads a file (the existing validation, encryption and store),
     * lists the patient's documents to pick it, and publishes with it; the patient downloads exactly that file. A
     * stranger practitioner can neither upload nor list; no practitioner downloads a file directly; an unknown document
     * is refused at publication.
     */
    @Test
    @SuppressWarnings("unchecked")
    void anAttachedPractitionerUploadsTheAttachmentAndThePatientDownloadsIt() throws Exception {
        Pro pro = practitioner("Ines", "Depot");
        Pro stranger = practitioner("Karim", "Ailleurs");
        String email = email("result-upload");
        UUID patient = console.createUser(email, PASSWORD);
        insertPatient(patient, email);
        appointment(pro.id(), patient);

        HttpResponse<String> uploaded = pro.browser().postMultipart("/api/patients/" + patient + "/documents",
                "Compte-rendu d'IRM", "irm.pdf", "application/pdf", DocumentTestFiles.PDF);
        assertThat(uploaded.statusCode()).as(uploaded.body()).isEqualTo(201);
        String documentId = (String) mapper.readValue(uploaded.body(), Map.class).get("id");
        HttpResponse<String> listed = pro.browser().call("/api/patients/" + patient + "/documents");
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        assertThat(listed.body()).contains(documentId);

        assertThat(stranger.browser().postMultipart("/api/patients/" + patient + "/documents", "Intrus", "x.pdf",
                "application/pdf", DocumentTestFiles.PDF).statusCode()).isEqualTo(403);
        assertThat(stranger.browser().call("/api/patients/" + patient + "/documents").statusCode()).isEqualTo(403);
        assertThat(pro.browser().getBytes("/api/patients/" + patient + "/documents/" + documentId).statusCode())
                .as("no direct download by a practitioner").isIn(403, 404);
        assertThat(pro.browser().postJson("/api/patients/" + patient + "/results",
                result("IRM", "Imagerie", "RAS.", UUID.randomUUID())).statusCode()).as("unknown document").isEqualTo(400);

        HttpResponse<String> published = pro.browser().postJson("/api/patients/" + patient + "/results",
                result("IRM", "Imagerie", "RAS.", UUID.fromString(documentId)));
        assertThat(published.statusCode()).as(published.body()).isEqualTo(201);

        Browser browser = new Browser();
        assertThat(browser.apiLogin(email, PASSWORD).statusCode()).isEqualTo(200);
        List<Map<String, Object>> mine = items(browser.call("/api/results/me"));
        assertThat(mine).singleElement().satisfies(row -> assertThat(row).containsEntry("documentId", documentId));
        HttpResponse<byte[]> file = browser.getBytes("/api/patients/" + patient + "/documents/" + documentId);
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.body()).isEqualTo(DocumentTestFiles.PDF);
        assertThat(jdbc.queryForObject("select uploaded_by from patient_documents where id = ?::uuid", UUID.class,
                documentId)).isEqualTo(pro.id());
    }

    /** US-13: the patient's list is paged; a page's new results become seen, the others stay new until shown. */
    @Test
    @SuppressWarnings("unchecked")
    void thePatientsResultsArePagedAndOnlyTheShownOnesBecomeSeen() throws Exception {
        Pro pro = practitioner("Mona", "Pages");
        String email = email("result-pages");
        UUID patient = console.createUser(email, PASSWORD);
        insertPatient(patient, email);
        appointment(pro.id(), patient);
        for (String title : List.of("Premier", "Deuxième", "Troisième")) {
            assertThat(pro.browser().postJson("/api/patients/" + patient + "/results", result(title, "Bio", "RAS.", null))
                    .statusCode()).isEqualTo(201);
        }
        Browser browser = new Browser();
        assertThat(browser.apiLogin(email, PASSWORD).statusCode()).isEqualTo(200);

        Map<String, Object> page0 = mapper.readValue(browser.call("/api/results/me?size=2").body(), Map.class);
        Map<String, Object> again = mapper.readValue(browser.call("/api/results/me?size=2").body(), Map.class);
        Map<String, Object> page1 = mapper.readValue(browser.call("/api/results/me?size=2&page=1").body(), Map.class);

        assertThat(page0).containsEntry("total", 3).containsEntry("unseen", 3);
        assertThat((List<Map<String, Object>>) page0.get("items")).extracting(row -> row.get("title"))
                .containsExactly("Troisième", "Deuxième");
        assertThat(again).as("the oldest was not shown, it stays new").containsEntry("unseen", 1);
        assertThat((List<Map<String, Object>>) page1.get("items")).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("title", "Premier").containsEntry("isNew", true));
        assertThat(browser.call("/api/results/me?size=0").statusCode()).isEqualTo(400);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return (List<Map<String, Object>>) mapper.readValue(response.body(), Map.class).get("items");
    }

    private static String withRequestId(String body, UUID requestId) {
        return body.substring(0, body.length() - 1) + ",\"requestId\":\"" + requestId + "\"}";
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
        appointment(practitioner, patient, PAST);
    }

    /** A practitioner holds one slot per start time: a second patient of theirs needs another start. */
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

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
