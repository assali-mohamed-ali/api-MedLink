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
 * MLink-108 (story MLink-28): a patient sees their own appointments, latest first, cancelled ones included, to cancel
 * or reschedule them. Always the caller's: no id in the URL, never another patient's appointment.
 */
class MyAppointmentsFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "My-Appointments-2026!";

    /** Tomorrow 09:00 UTC: the slots of a test start from there. */
    static final Instant DAY = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS)
            .plus(9, ChronoUnit.HOURS);

    final KeycloakConsole console = new KeycloakConsole();
    final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aPatientSeesOnlyTheirOwnAppointmentsLatestFirstCancelledOnesIncluded() throws Exception {
        UUID practitioner = insertPractitioner("Rim", "Jaziri", insertSpecialty("Cardiologie (mes rendez-vous)"));
        UUID clinic = insertClinic("Clinique Mes RDV Sfax");
        UUID early = insertSlot(practitioner, clinic, DAY, "FREE");
        UUID late = insertSlot(practitioner, clinic, DAY.plus(2, ChronoUnit.DAYS), "FREE");
        UUID theirs = insertSlot(practitioner, clinic, DAY.plus(1, ChronoUnit.DAYS), "FREE");
        UUID cancelledSlot = insertSlot(practitioner, clinic, DAY.plus(3, ChronoUnit.DAYS), "FREE");
        Patient a = patient();
        Patient b = patient();
        String earlyId = book(a, early);
        String lateId = book(a, late);
        book(b, theirs);
        UUID cancelled = UUID.randomUUID();
        jdbc.update("""
                insert into appointments (id, slot_id, patient_id, booked_by, status, cancelled_at)
                values (?, ?, ?, ?, 'CANCELLED', now())""", cancelled, cancelledSlot, a.id(), a.id());

        HttpResponse<String> response = a.browser().call("/api/appointments/me");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        Map<String, Object> body = body(response);
        assertThat(ids(body)).containsExactly(cancelled.toString(), lateId, earlyId);
        assertThat(((Number) body.get("total")).longValue()).isEqualTo(3);
        Map<String, Object> first = items(body).get(0);
        assertThat(first.get("status")).isEqualTo("CANCELLED");
        assertThat(first.get("clinic")).isEqualTo(Map.of("id", clinic.toString(), "name", "Clinique Mes RDV Sfax"));
        assertThat(first.get("practitioner"))
                .isEqualTo(Map.of("id", practitioner.toString(), "firstName", "Rim", "lastName", "Jaziri"));
        assertThat(first.get("startsAt")).isEqualTo(DAY.plus(3, ChronoUnit.DAYS).toString());
        assertThat(items(body).get(1).get("status")).isEqualTo("BOOKED");
        // Nothing of the practitioner but their names
        assertThat(response.body()).doesNotContain("@");
    }

    @Test
    void theListIsPaged() throws Exception {
        UUID practitioner = insertPractitioner("Sami", "Trabelsi", insertSpecialty("Cardiologie (pages RDV)"));
        UUID clinic = insertClinic("Clinique Mes RDV Tunis");
        Patient patient = patient();
        String first = book(patient, insertSlot(practitioner, clinic, DAY, "FREE"));
        String second = book(patient, insertSlot(practitioner, clinic, DAY.plus(30, ChronoUnit.MINUTES), "FREE"));

        Map<String, Object> page0 = body(patient.browser().call("/api/appointments/me?page=0&size=1"));
        Map<String, Object> page1 = body(patient.browser().call("/api/appointments/me?page=1&size=1"));

        assertThat(ids(page0)).containsExactly(second);
        assertThat(ids(page1)).containsExactly(first);
        assertThat(((Number) page0.get("total")).longValue()).isEqualTo(2);
    }

    @Test
    void aPatientWithNoAppointmentGetsAnEmptyList() throws Exception {
        Map<String, Object> body = body(patient().browser().call("/api/appointments/me"));

        assertThat(items(body)).isEmpty();
        assertThat(((Number) body.get("total")).longValue()).isZero();
    }

    @Test
    void pagesHoldOneToFiftyAppointments() throws Exception {
        Browser patient = patient().browser();

        HttpResponse<String> tooBig = patient.call("/api/appointments/me?size=51");
        HttpResponse<String> negative = patient.call("/api/appointments/me?page=-1");

        assertThat(tooBig.statusCode()).isEqualTo(400);
        assertThat(body(tooBig).get("details"))
                .isEqualTo(Map.of("size", "La taille de page doit être comprise entre 1 et 50."));
        assertThat(negative.statusCode()).isEqualTo(400);
        assertThat(body(negative).get("details")).isEqualTo(Map.of("page", "Le numéro de page ne peut pas être négatif."));
    }

    @Test
    void anAnonymousVisitorIsAskedToLogIn() throws Exception {
        assertThat(new Browser().call("/api/appointments/me").statusCode()).isEqualTo(401);
    }

    @Test
    void aPractitionerAndStaffAreRefused() throws Exception {
        Browser practitioner = new Browser().login("practitioner.dev@medlink.local", DEV_USERS_PASSWORD);
        Browser staff = new Browser().login("staff.dev@medlink.local", DEV_USERS_PASSWORD);

        for (String path : List.of("/api/appointments/me", "/api/appointments/me?size=abc")) {
            assertThat(practitioner.call(path).statusCode()).as("practitioner " + path).isEqualTo(403);
            assertThat(staff.call(path).statusCode()).as("staff " + path).isEqualTo(403);
        }
    }

    /** Books through the API, as the patient would; returns the appointment's id. */
    private String book(Patient patient, UUID slot) throws Exception {
        HttpResponse<String> response = patient.browser().postJson("/api/appointments", "{\"slotId\":\"" + slot + "\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return (String) body(response).get("id");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpResponse<String> response) {
        return json.readValue(response.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
    }

    private static List<Object> ids(Map<String, Object> body) {
        return items(body).stream().map(item -> item.get("id")).toList();
    }

    /** A logged-in patient as registration leaves them: a users row (the login) and a patients row. */
    record Patient(UUID id, Browser browser) {}

    private Patient patient() throws Exception {
        String email = uniqueEmail("my-appointments");
        UUID id = console.createUser(email, PASSWORD);
        Browser browser = new Browser();
        assertThat(browser.apiLogin(email, PASSWORD).statusCode()).as("patient logged in").isEqualTo(200);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Ben Salah', ?)",
                id, Date.valueOf("1990-04-12"));
        return new Patient(id, browser);
    }

    // ---- test data, written straight to the database ---------------------------------------------

    /** Specialty names are unique: each test uses its own. */
    private UUID insertSpecialty(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", id, name);
        return id;
    }

    private UUID insertPractitioner(String firstName, String lastName, UUID specialty) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)",
                id, "practitioner-" + id, "practitioner-" + id + "@example.com");
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, ?, ?, ?)",
                id, firstName, lastName, specialty);
        return id;
    }

    private UUID insertClinic(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, ?, ?)", id, name, name);
        return id;
    }

    /** The practitioner works in the clinic, then the slot (30 minutes). */
    private UUID insertSlot(UUID practitioner, UUID clinic, Instant start, String status) {
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?) on conflict do nothing",
                practitioner, clinic);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, ?)",
                id, practitioner, clinic, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)), status);
        return id;
    }
}
