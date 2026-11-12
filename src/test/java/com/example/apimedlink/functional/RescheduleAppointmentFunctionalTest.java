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
 * MLink-107 (story MLink-28): a patient moves their appointment to another free slot in one transaction: the new slot
 * is booked, the old appointment cancelled and its slot free again. When the new slot is taken, nothing changes: the
 * patient keeps their appointment. Same access rules and 12-hour deadline as the cancel (MLink-106).
 */
class RescheduleAppointmentFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Reschedule-2026!";

    /** Two days ahead, 09:00 UTC: well before the 12-hour deadline. */
    static final Instant LATER = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(2, ChronoUnit.DAYS)
            .plus(9, ChronoUnit.HOURS);

    final KeycloakConsole console = new KeycloakConsole();
    final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theNewSlotIsBookedTheOldAppointmentCancelledAndItsSlotFree() throws Exception {
        UUID practitioner = insertPractitioner("Rim", "Jaziri", insertSpecialty("Cardiologie (report)"));
        UUID clinic = insertClinic("Clinique Report Sfax");
        UUID oldSlot = insertSlot(practitioner, clinic, LATER, "FREE");
        UUID newSlot = insertSlot(practitioner, clinic, LATER.plus(1, ChronoUnit.DAYS), "FREE");
        Patient holder = patient();
        String old = book(holder, oldSlot);

        HttpResponse<String> response = reschedule(holder.browser(), old, "{\"newSlotId\":\"" + newSlot + "\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        Map<String, Object> body = body(response);
        String moved = (String) body.get("id");
        assertThat(moved).isNotEqualTo(old);
        assertThat(body.get("status")).isEqualTo("BOOKED");
        assertThat(body.get("startsAt")).isEqualTo(LATER.plus(1, ChronoUnit.DAYS).toString());
        assertThat(body.get("clinic")).isEqualTo(Map.of("id", clinic.toString(), "name", "Clinique Report Sfax"));
        assertThat(status("appointments", old)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForMap("select status, patient_id, booked_by, slot_id from appointments where id = ?::uuid", moved))
                .containsEntry("status", "BOOKED").containsEntry("patient_id", holder.id())
                .containsEntry("booked_by", holder.id()).containsEntry("slot_id", newSlot);
        assertThat(status("slots", oldSlot.toString())).isEqualTo("FREE");
        assertThat(status("slots", newSlot.toString())).isEqualTo("BOOKED");
    }

    /** The point of one transaction: the new slot is taken, so the patient keeps the appointment they had. */
    @Test
    void whenTheNewSlotIsTakenTheOldAppointmentIsKeptAsItWas() throws Exception {
        UUID practitioner = insertPractitioner("Omar", "Gharbi", insertSpecialty("Pédiatrie (report pris)"));
        UUID clinic = insertClinic("Clinique Report Sousse");
        UUID oldSlot = insertSlot(practitioner, clinic, LATER, "FREE");
        UUID newSlot = insertSlot(practitioner, clinic, LATER.plus(1, ChronoUnit.DAYS), "FREE");
        Patient holder = patient();
        String old = book(holder, oldSlot);
        book(patient(), newSlot);

        HttpResponse<String> response = reschedule(holder.browser(), old, "{\"newSlotId\":\"" + newSlot + "\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        assertThat(body(response).get("code")).isEqualTo("SLOT_TAKEN");
        assertThat(status("appointments", old)).isEqualTo("BOOKED");
        assertThat(status("slots", oldSlot.toString())).isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject("select count(*) from appointments where patient_id = ?", Integer.class, holder.id()))
                .isEqualTo(1);
    }

    @Test
    void anUnknownNewSlotIsNotFoundAndTheOldAppointmentIsKept() throws Exception {
        UUID oldSlot = insertSlot(insertPractitioner("Leila", "Ben Ali", insertSpecialty("Cardiologie (report inconnu)")),
                insertClinic("Clinique Report Tunis"), LATER, "FREE");
        Patient holder = patient();
        String old = book(holder, oldSlot);

        HttpResponse<String> response = reschedule(holder.browser(), old, "{\"newSlotId\":\"" + UUID.randomUUID() + "\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
        assertThat(status("appointments", old)).isEqualTo("BOOKED");
    }

    @Test
    void lessThanTwelveHoursBeforeTheCurrentAppointmentTheMoveIsRefused() throws Exception {
        UUID practitioner = insertPractitioner("Nadia", "Haddad", insertSpecialty("Médecine générale (report délai)"));
        UUID clinic = insertClinic("Clinique Report Délai");
        UUID oldSlot = insertSlot(practitioner, clinic, Instant.now().plus(6, ChronoUnit.HOURS), "FREE");
        UUID newSlot = insertSlot(practitioner, clinic, LATER, "FREE");
        Patient holder = patient();
        String old = book(holder, oldSlot);

        HttpResponse<String> response = reschedule(holder.browser(), old, "{\"newSlotId\":\"" + newSlot + "\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
        assertThat(body(response).get("code")).isEqualTo("CANCEL_DEADLINE_PASSED");
        assertThat(status("appointments", old)).isEqualTo("BOOKED");
        assertThat(status("slots", newSlot.toString())).isEqualTo("FREE");
    }

    @Test
    void aCancelledAppointmentCannotBeMoved() throws Exception {
        UUID practitioner = insertPractitioner("Sami", "Trabelsi", insertSpecialty("Cardiologie (report annulé)"));
        UUID clinic = insertClinic("Clinique Report Annulé");
        UUID oldSlot = insertSlot(practitioner, clinic, LATER, "FREE");
        UUID newSlot = insertSlot(practitioner, clinic, LATER.plus(1, ChronoUnit.DAYS), "FREE");
        Patient holder = patient();
        String old = book(holder, oldSlot);
        assertThat(holder.browser().postJson("/api/appointments/" + old + "/cancel", "").statusCode()).isEqualTo(204);

        HttpResponse<String> response = reschedule(holder.browser(), old, "{\"newSlotId\":\"" + newSlot + "\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        Map<String, Object> body = body(response);
        assertThat(body.get("code")).isEqualTo("APPOINTMENT_CANCELLED");
        assertThat(body.get("message")).isEqualTo("Ce rendez-vous est annulé : réservez un nouveau créneau.");
        assertThat(status("slots", newSlot.toString())).isEqualTo("FREE");
    }

    /** Never tell whether an appointment exists: someone else's, an unknown id and an unreadable id get one answer. */
    @Test
    void anotherPatientAnUnknownIdAndAnUnreadableIdGetTheSame403AndNothingChanges() throws Exception {
        UUID practitioner = insertPractitioner("Hela", "Mansour", insertSpecialty("Dermatologie (report)"));
        UUID clinic = insertClinic("Clinique Report Autre");
        UUID oldSlot = insertSlot(practitioner, clinic, LATER, "FREE");
        UUID newSlot = insertSlot(practitioner, clinic, LATER.plus(1, ChronoUnit.DAYS), "FREE");
        String old = book(patient(), oldSlot);
        Browser stranger = patient().browser();
        String move = "{\"newSlotId\":\"" + newSlot + "\"}";

        for (String id : List.of(old, UUID.randomUUID().toString(), "abc")) {
            HttpResponse<String> response = reschedule(stranger, id, move);
            assertThat(response.statusCode()).as(id + " " + response.body()).isEqualTo(403);
            assertThat(body(response).get("code")).isEqualTo("FORBIDDEN");
        }
        assertThat(status("appointments", old)).isEqualTo("BOOKED");
        assertThat(status("slots", newSlot.toString())).isEqualTo("FREE");
    }

    @Test
    void aMissingOrUnreadableNewSlotIdIsNamedInOurErrorBody() throws Exception {
        UUID oldSlot = insertSlot(insertPractitioner("Amine", "Kacem", insertSpecialty("Pédiatrie (report corps)")),
                insertClinic("Clinique Report Corps"), LATER, "FREE");
        Patient holder = patient();
        String old = book(holder, oldSlot);

        assertRefused(reschedule(holder.browser(), old, "{}"), "Le nouveau créneau est obligatoire.");
        assertRefused(reschedule(holder.browser(), old, "{\"newSlotId\":\"abc\"}"), "Identifiant invalide.");
        assertThat(status("appointments", old)).isEqualTo("BOOKED");
    }

    @Test
    void anAnonymousVisitorIsAskedToLogIn() throws Exception {
        assertThat(reschedule(new Browser(), UUID.randomUUID().toString(), "{}").statusCode()).isEqualTo(401);
    }

    @Test
    void aPractitionerAndStaffAreRefusedEvenWithAnUnreadableBody() throws Exception {
        Browser practitioner = new Browser().login("practitioner.dev@medlink.local", DEV_USERS_PASSWORD);
        Browser staff = new Browser().login("staff.dev@medlink.local", DEV_USERS_PASSWORD);
        String id = UUID.randomUUID().toString();

        for (String body : List.of("{\"newSlotId\":\"" + UUID.randomUUID() + "\"}", "{}", "not json")) {
            assertThat(reschedule(practitioner, id, body).statusCode()).as("practitioner " + body).isEqualTo(403);
            assertThat(reschedule(staff, id, body).statusCode()).as("staff " + body).isEqualTo(403);
        }
    }

    private static HttpResponse<String> reschedule(Browser browser, String appointment, String body) throws Exception {
        return browser.postJson("/api/appointments/" + appointment + "/reschedule", body);
    }

    private String status(String table, String id) {
        return jdbc.queryForObject("select status from " + table + " where id = ?::uuid", String.class, id);
    }

    private void assertRefused(HttpResponse<String> response, String message) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        Map<String, Object> body = body(response);
        assertThat(body.get("code")).isEqualTo("VALIDATION_FAILED");
        assertThat(body.get("details")).isEqualTo(Map.of("newSlotId", message));
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

    /** A logged-in patient as registration leaves them: a users row (the login) and a patients row. */
    record Patient(UUID id, Browser browser) {}

    private Patient patient() throws Exception {
        String email = uniqueEmail("reschedule");
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
