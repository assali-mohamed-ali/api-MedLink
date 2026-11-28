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
 * US-16 (staff side): the administrative staff list the pending deletion requests and decide them. A patient who had a
 * consultation is retained for 20 years after it: approval is refused (409) while the period runs; a refusal needs a note
 * the patient can read; a decided request cannot be cancelled; a refused one can be asked again; patients and
 * practitioners cannot decide. Nothing is deleted. NOT RUN in the session that wrote it (no Docker).
 */
class AdminDeletionRequestFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Admin-Deletion-2026!";
    static final String PATIENT_PATH = "/api/patients/me/deletion-request";

    final KeycloakConsole console = new KeycloakConsole();
    final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    JdbcTemplate jdbc;

    record Patient(UUID id, Browser browser) {}

    @Test
    @SuppressWarnings("unchecked")
    void staffSeeThePendingRequestWithItsRetentionAndApproveOnlyWhenNothingMustBeKept() throws Exception {
        Browser staff = staff();
        Patient fresh = patient("Nour", "SansConsultation");
        Patient seen = patient("Samir", "AvecConsultation");
        consultation(seen.id(), Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS));
        assertThat(fresh.browser().postJson(PATIENT_PATH, "").statusCode()).isEqualTo(200);
        assertThat(seen.browser().postJson(PATIENT_PATH, "").statusCode()).isEqualTo(200);

        HttpResponse<String> list = staff.call("/api/admin/deletion-requests");

        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
        List<Map<String, Object>> pending = mapper.readValue(list.body(), List.class);
        Map<String, Object> freshRow = row(pending, fresh.id());
        Map<String, Object> seenRow = row(pending, seen.id());
        assertThat(freshRow.get("retainedUntil")).isNull();
        assertThat(seenRow.get("retainedUntil")).isNotNull();
        assertThat(seenRow.get("lastConsultationAt")).isNotNull();
        assertThat(list.body()).doesNotContain("birthDate");

        HttpResponse<String> blocked = staff.postJson(decision(seen.id()), "{\"decision\":\"APPROVED\"}");
        assertThat(blocked.statusCode()).as(blocked.body()).isEqualTo(409);
        assertThat(blocked.body()).contains("RETENTION_APPLIES");

        HttpResponse<String> approved = staff.postJson(decision(fresh.id()), "{\"decision\":\"APPROVED\"}");
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(mapper.readValue(approved.body(), Map.class)).containsEntry("status", "APPROVED");
        assertThat(jdbc.queryForObject("select count(*) from patients where id = ?", Integer.class, fresh.id()))
                .as("approving deletes nothing yet").isEqualTo(1);
        assertThat(jdbc.queryForObject("select decided_by from deletion_requests where patient_id = ?", UUID.class,
                fresh.id())).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from audit_entries where action = 'ACCOUNT_UPDATED' "
                + "and resource_type = 'PATIENT_DELETION_DECISION' and resource_id = ?", Integer.class,
                fresh.id().toString())).isEqualTo(1);
        assertThat(staff.postJson(decision(fresh.id()), "{\"decision\":\"REJECTED\",\"note\":\"x\"}").statusCode())
                .as("already decided").isEqualTo(409);
        assertThat(fresh.browser().delete(PATIENT_PATH).statusCode()).as("the closed account's session is over")
                .isEqualTo(401);
    }

    /**
     * Request, decision and closure are separate steps; anonymisation and purge are not done. An approval closes the
     * account: the open session is refused at once, Keycloak no longer lets the patient log in, the patient is told
     * by mail (nothing medical), the closure time is recorded, and the appointments, the request and the audit trail
     * stay. Mutation check: without {@code registerWithin} in DeletionReviewService the login still succeeds.
     */
    @Test
    @SuppressWarnings("unchecked")
    void anApprovalClosesTheAccountRevokesItsSessionsAndKeepsTheRecords() throws Exception {
        Browser staff = staff();
        String email = email("deletion-closure");
        UUID id = console.createUser(email, PASSWORD);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?) on conflict (id) do nothing", id, email, email);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Nadia', 'Cloture', ?)",
                id, Date.valueOf("1990-04-12"));
        Browser patient = new Browser();
        assertThat(patient.apiLogin(email, PASSWORD).statusCode()).isEqualTo(200);
        // A future appointment only: nothing has been consulted yet, so no retention period runs
        consultation(id, Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS));
        assertThat(patient.postJson(PATIENT_PATH, "").statusCode()).isEqualTo(200);
        int auditBefore = jdbc.queryForObject("select count(*) from audit_entries", Integer.class);

        HttpResponse<String> approved = staff.postJson(decision(id), "{\"decision\":\"APPROVED\"}");

        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat((String) mapper.readValue(approved.body(), Map.class).get("accountClosedAt")).isNotNull();
        assertThat(patient.call("/api/patients/me").statusCode()).as("open session refused").isEqualTo(401);
        HttpResponse<String> login = new Browser().apiLogin(email, PASSWORD);
        assertThat(login.statusCode()).as("login refused: " + login.body()).isNotEqualTo(200);
        assertThat(console.read("/users/{id}", id)).extracting(user -> ((Map<String, Object>) user).get("enabled"))
                .isEqualTo(false);
        assertThat(new Mailbox().to(email, "Votre compte MedLink est clôturé")).hasSize(1);
        assertThat(jdbc.queryForObject("select account_closed_at is not null from deletion_requests where patient_id = ?",
                Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from patients where id = ?", Integer.class, id))
                .as("no anonymisation, no purge").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from appointments where patient_id = ?", Integer.class, id))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_entries", Integer.class))
                .as("the trail only grows").isGreaterThan(auditBefore);
        assertThat(jdbc.queryForList("select status from account_operations where user_id = ? "
                + "and action = 'ACCOUNT_DISABLED'", String.class, id)).containsExactly("DONE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRefusalNeedsANoteThePatientReadsAndTheyCanAskAgain() throws Exception {
        Browser staff = staff();
        Patient patient = patient("Lina", "Refusee");
        assertThat(patient.browser().postJson(PATIENT_PATH, "").statusCode()).isEqualTo(200);

        assertThat(staff.postJson(decision(patient.id()), "{\"decision\":\"REJECTED\"}").statusCode())
                .as("no note").isEqualTo(400);
        HttpResponse<String> rejected = staff.postJson(decision(patient.id()),
                "{\"decision\":\"REJECTED\",\"note\":\"Dossier à conserver : suivi en cours.\"}");

        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(200);
        Map<String, Object> seen = mapper.readValue(patient.browser().call(PATIENT_PATH).body(), Map.class);
        assertThat(seen).containsEntry("status", "REJECTED").containsEntry("decisionNote",
                "Dossier à conserver : suivi en cours.");
        Map<String, Object> again = mapper.readValue(patient.browser().postJson(PATIENT_PATH, "").body(), Map.class);
        assertThat(again).containsEntry("status", "PENDING");
    }

    @Test
    void patientsPractitionersAndAnonymousCallersCannotDecideOrList() throws Exception {
        Patient patient = patient("Omar", "Curieux");
        String proEmail = email("deletion-admin-pro");
        console.createUser(proEmail, PASSWORD, "/practitioners");
        Browser pro = new Browser().login(proEmail, PASSWORD);

        for (Browser other : List.of(patient.browser(), pro)) {
            assertThat(other.call("/api/admin/deletion-requests").statusCode()).isEqualTo(403);
            assertThat(other.postJson(decision(patient.id()), "{\"decision\":\"APPROVED\"}").statusCode()).isEqualTo(403);
        }
        assertThat(new Browser().call("/api/admin/deletion-requests").statusCode()).isEqualTo(401);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(List<Map<String, Object>> rows, UUID patientId) {
        return rows.stream().filter(row -> patientId.toString().equals(row.get("patientId"))).findFirst()
                .orElseThrow(() -> new AssertionError("no request of " + patientId));
    }

    private static String decision(UUID patientId) {
        return "/api/admin/deletion-requests/" + patientId + "/decision";
    }

    private Browser staff() throws Exception {
        String email = email("deletion-staff-decider");
        console.createUser(email, PASSWORD, "/administrative-staff");
        return new Browser().login(email, PASSWORD);
    }

    private Patient patient(String firstName, String lastName) throws Exception {
        String email = email("deletion-admin-patient");
        UUID id = console.createUser(email, PASSWORD);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?) on conflict (id) do nothing", id, email, email);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, ?, ?, ?)",
                id, firstName, lastName, Date.valueOf("1990-04-12"));
        Browser browser = new Browser();
        assertThat(browser.apiLogin(email, PASSWORD).statusCode()).isEqualTo(200);
        return new Patient(id, browser);
    }

    /** A BOOKED appointment of the patient on a slot that has started: a consultation that took place. */
    private void consultation(UUID patient, Instant start) {
        UUID practitioner = UUID.randomUUID();
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", practitioner, "pro-" + practitioner,
                "pro-" + practitioner + "@example.com");
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Leila', 'Ben Ali', ?)",
                practitioner, specialty);
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
