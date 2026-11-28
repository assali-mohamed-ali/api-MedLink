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
 * US-22: a practitioner searches their own patients by name, {@code GET /api/practitioner/patients?q=}. A patient is
 * linked by a BOOKED appointment, or by a report the practitioner wrote (a link that outlives the appointment); a
 * cancelled appointment alone links nobody; another practitioner's patients never appear. The same link opens the
 * patient's record. Patients, staff and anonymous callers are refused. NOT RUN in the session that wrote it (no Docker).
 */
class PractitionerPatientSearchFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Patient-Search-2026!";
    static final Instant PAST = Instant.now().truncatedTo(ChronoUnit.DAYS).minus(2, ChronoUnit.DAYS).plus(9, ChronoUnit.HOURS);

    final KeycloakConsole console = new KeycloakConsole();
    final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    JdbcTemplate jdbc;

    record Pro(UUID id, Browser browser) {}

    @Test
    void aPractitionerFindsOnlyTheirLinkedPatientsByPartOfTheName() throws Exception {
        String tag = "Zq" + UUID.randomUUID().toString().substring(0, 6);
        Pro pro = practitioner();
        Pro other = practitioner();
        UUID mine = patient("Amel", tag + "Mine");
        UUID alsoMine = patient(tag + "Prénom", "Dupont");
        UUID notMine = patient("Amel", tag + "Other");
        UUID unlinked = patient("Amel", tag + "Nobody");
        booked(pro.id(), mine, PAST, "BOOKED");
        booked(pro.id(), alsoMine, PAST.plus(1, ChronoUnit.HOURS), "BOOKED");
        booked(other.id(), notMine, PAST.plus(2, ChronoUnit.HOURS), "BOOKED");

        HttpResponse<String> response = pro.browser().call("/api/practitioner/patients?q=" + tag.toLowerCase());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        List<Map<String, Object>> found = list(response);
        assertThat(found).extracting(row -> row.get("id")).containsExactlyInAnyOrder(mine.toString(), alsoMine.toString());
        assertThat(found.get(0)).containsOnlyKeys("id", "firstName", "lastName", "recordNumber");
        assertThat(response.body()).doesNotContain(notMine.toString()).doesNotContain(unlinked.toString());
    }

    @Test
    void aCancelledAppointmentAloneDoesNotLinkButAReportDoes() throws Exception {
        String tag = "Zr" + UUID.randomUUID().toString().substring(0, 6);
        Pro pro = practitioner();
        UUID cancelledOnly = patient("Amel", tag + "Cancelled");
        UUID reported = patient("Amel", tag + "Reported");
        booked(pro.id(), cancelledOnly, PAST, "CANCELLED");
        UUID appointment = booked(pro.id(), reported, PAST.plus(1, ChronoUnit.HOURS), "BOOKED");
        assertThat(pro.browser().postJson("/api/patients/" + reported + "/reports", "{\"content\": \"Suivi.\"}").statusCode())
                .isEqualTo(201);
        jdbc.update("update appointments set status = 'CANCELLED' where id = ?", appointment);

        List<Map<String, Object>> found = list(pro.browser().call("/api/practitioner/patients?q=" + tag.toLowerCase()));

        assertThat(found).extracting(row -> row.get("id")).containsExactly(reported.toString());
        assertThat(pro.browser().call("/api/patients/" + reported + "/record").statusCode()).isEqualTo(200);
        assertThat(pro.browser().call("/api/patients/" + cancelledOnly + "/record").statusCode()).isEqualTo(403);
    }

    @Test
    void wildcardsAreTakenLiterallyAndAnOverlongQueryIsRefused() throws Exception {
        Pro pro = practitioner();
        UUID patient = patient("Amel", "Wild" + UUID.randomUUID().toString().substring(0, 6));
        booked(pro.id(), patient, PAST, "BOOKED");

        assertThat(list(pro.browser().call("/api/practitioner/patients?q=%25"))).isEmpty();
        assertThat(list(pro.browser().call("/api/practitioner/patients?q=_"))).isEmpty();
        assertThat(pro.browser().call("/api/practitioner/patients?q=" + "a".repeat(101)).statusCode()).isEqualTo(400);
    }

    @Test
    void patientsStaffAndAnonymousCallersAreRefused() throws Exception {
        String staffEmail = email("search-staff");
        console.createUser(staffEmail, PASSWORD, "/administrative-staff");
        Browser staff = new Browser().login(staffEmail, PASSWORD);
        String patientEmail = email("search-patient");
        console.createUser(patientEmail, PASSWORD);
        Browser patient = new Browser();
        assertThat(patient.apiLogin(patientEmail, PASSWORD).statusCode()).isEqualTo(200);

        assertThat(staff.call("/api/practitioner/patients?q=a").statusCode()).isEqualTo(403);
        assertThat(patient.call("/api/practitioner/patients?q=a").statusCode()).isEqualTo(403);
        assertThat(new Browser().call("/api/practitioner/patients?q=a").statusCode()).isEqualTo(401);
    }

    /**
     * US-22: a record number finds the linked patient whatever way it is typed; a homonym of a linked patient and a
     * stranger's record number find nothing; every patient listed opens (record 200), the others do not (403).
     */
    @Test
    void aRecordNumberFindsOnlyALinkedPatientAndEveryResultOpensItsRecord() throws Exception {
        String tag = "Rn" + UUID.randomUUID().toString().substring(0, 6);
        Pro pro = practitioner();
        UUID linked = patient("Sami", tag + "Lie");
        UUID homonym = patient("Sami", tag + "Lie");
        UUID stranger = patient("Nour", tag + "Inconnu");
        booked(pro.id(), linked, PAST, "BOOKED");
        long linkedNumber = jdbc.queryForObject("select record_number from patients where id = ?", Long.class, linked);
        long strangerNumber = jdbc.queryForObject("select record_number from patients where id = ?", Long.class, stranger);
        String label = String.format("MED-%06d", linkedNumber);

        for (String typed : List.of(label, label.toLowerCase(), "MED " + linkedNumber, String.format("%06d", linkedNumber))) {
            List<Map<String, Object>> found = list(pro.browser().call("/api/practitioner/patients?q="
                    + java.net.URLEncoder.encode(typed, java.nio.charset.StandardCharsets.UTF_8)));
            assertThat(found).as(typed).extracting(row -> row.get("id")).containsExactly(linked.toString());
            assertThat(found.get(0)).containsEntry("recordNumber", label);
        }
        assertThat(list(pro.browser().call("/api/practitioner/patients?q=" + String.format("MED-%06d", strangerNumber))))
                .as("a stranger's number").isEmpty();
        List<Map<String, Object>> byName = list(pro.browser().call("/api/practitioner/patients?q="
                + java.net.URLEncoder.encode("sami " + tag.toLowerCase(), java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(byName).as("full name, the homonym left out").extracting(row -> row.get("id"))
                .containsExactly(linked.toString());

        for (Map<String, Object> row : byName) {
            assertThat(pro.browser().call("/api/patients/" + row.get("id") + "/record").statusCode()).isEqualTo(200);
        }
        assertThat(pro.browser().call("/api/patients/" + homonym + "/record").statusCode()).isEqualTo(403);
        assertThat(pro.browser().call("/api/patients/" + stranger + "/record").statusCode()).isEqualTo(403);
    }

    /** US-22: the search is paged; {@code total} counts every match and the pages do not overlap. */
    @Test
    @SuppressWarnings("unchecked")
    void theSearchIsPagedWithATotal() throws Exception {
        String tag = "Pg" + UUID.randomUUID().toString().substring(0, 6);
        Pro pro = practitioner();
        UUID first = patient("Amel", tag + "A");
        UUID second = patient("Amel", tag + "B");
        booked(pro.id(), first, PAST, "BOOKED");
        booked(pro.id(), second, PAST.plus(1, ChronoUnit.HOURS), "BOOKED");
        String q = "/api/practitioner/patients?q=" + tag.toLowerCase();

        Map<String, Object> page0 = mapper.readValue(pro.browser().call(q + "&size=1&page=0").body(), Map.class);
        Map<String, Object> page1 = mapper.readValue(pro.browser().call(q + "&size=1&page=1").body(), Map.class);

        assertThat(page0).containsEntry("total", 2).containsEntry("size", 1).containsEntry("page", 0);
        assertThat(((List<Map<String, Object>>) page0.get("items"))).extracting(row -> row.get("id"))
                .containsExactly(first.toString());
        assertThat(((List<Map<String, Object>>) page1.get("items"))).extracting(row -> row.get("id"))
                .containsExactly(second.toString());
        assertThat(pro.browser().call(q + "&size=51").statusCode()).isEqualTo(400);
        assertThat(pro.browser().call(q + "&page=-1").statusCode()).isEqualTo(400);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return (List<Map<String, Object>>) mapper.readValue(response.body(), Map.class).get("items");
    }

    private Pro practitioner() throws Exception {
        String email = email("search-pro");
        UUID id = console.createUser(email, PASSWORD, "/practitioners");
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?) on conflict (id) do nothing", id, email, email);
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Leila', 'Ben Ali', ?)",
                id, specialty);
        return new Pro(id, new Browser().login(email, PASSWORD));
    }

    private UUID patient(String firstName, String lastName) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", id, "patient-" + id, "patient-" + id + "@example.com");
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, ?, ?, ?)",
                id, firstName, lastName, Date.valueOf("1990-04-12"));
        return id;
    }

    /** One clinic of the practitioner's, one started slot, one appointment of the patient on it with this status. */
    private UUID booked(UUID practitioner, UUID patient, Instant start, String status) {
        UUID clinic = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, ?, 'Sousse')", clinic, "Clinique " + clinic);
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", practitioner, clinic);
        UUID slot = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, 'BOOKED')",
                slot, practitioner, clinic, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)));
        UUID id = UUID.randomUUID();
        jdbc.update("insert into appointments (id, slot_id, patient_id, booked_by, status) values (?, ?, ?, ?, ?)",
                id, slot, patient, patient, status);
        return id;
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
