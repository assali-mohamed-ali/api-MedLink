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
 * MLink-106 (story MLink-28): a patient cancels their own appointment, up to 12 hours before it starts (H-03). The slot
 * becomes free again. Anyone else gets the same 403 whether the appointment exists or not; a late cancel is 422.
 */
class CancelAppointmentFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Cancel-Booking-2026!";

    /** Two days ahead, 09:00 UTC: well before the 12-hour deadline. */
    static final Instant LATER = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(2, ChronoUnit.DAYS)
            .plus(9, ChronoUnit.HOURS);

    final KeycloakConsole console = new KeycloakConsole();
    final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theHolderCancelsTheSlotIsFreeAgainAndBackInTheSearch() throws Exception {
        UUID practitioner = insertPractitioner("Rim", "Jaziri", insertSpecialty("Cardiologie (annulation)"));
        UUID slot = insertSlot(practitioner, insertClinic("Clinique Annulation Sfax"), LATER, "FREE");
        Patient holder = patient();
        String appointment = book(holder, slot);

        HttpResponse<String> response = cancel(holder.browser(), appointment);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
        Map<String, Object> row = jdbc.queryForMap("select status, cancelled_at from appointments where id = ?::uuid", appointment);
        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(row.get("cancelled_at")).isNotNull();
        assertThat(jdbc.queryForObject("select status from slots where id = ?", String.class, slot)).isEqualTo("FREE");
        Map<String, Object> search = body(holder.browser().call("/api/slots?practitionerId=" + practitioner
                + "&from=" + LATER.minus(1, ChronoUnit.HOURS) + "&to=" + LATER.plus(1, ChronoUnit.HOURS)));
        assertThat(slotIds(search)).containsExactly(slot.toString());
    }

    /** Never tell whether an appointment exists: someone else's, an unknown id and an unreadable id get one answer. */
    @Test
    void anotherPatientAnUnknownIdAndAnUnreadableIdGetTheSame403AndNothingChanges() throws Exception {
        UUID slot = insertSlot(insertPractitioner("Omar", "Gharbi", insertSpecialty("Pédiatrie (annulation)")),
                insertClinic("Clinique Annulation Sousse"), LATER, "FREE");
        String appointment = book(patient(), slot);
        Browser stranger = patient().browser();

        HttpResponse<String> theirs = cancel(stranger, appointment);
        HttpResponse<String> unknown = cancel(stranger, UUID.randomUUID().toString());
        HttpResponse<String> unreadable = cancel(stranger, "abc");

        for (HttpResponse<String> response : List.of(theirs, unknown, unreadable)) {
            assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
            assertThat(body(response).get("code")).isEqualTo("FORBIDDEN");
        }
        assertThat(body(theirs).get("message")).isEqualTo(body(unknown).get("message"));
        assertThat(jdbc.queryForObject("select status from appointments where id = ?::uuid", String.class, appointment))
                .isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject("select status from slots where id = ?", String.class, slot)).isEqualTo("BOOKED");
    }

    @Test
    void lessThanTwelveHoursBeforeTheStartTheCancelIsRefusedAndNothingChanges() throws Exception {
        UUID slot = insertSlot(insertPractitioner("Leila", "Ben Ali", insertSpecialty("Cardiologie (délai)")),
                insertClinic("Clinique Annulation Tunis"), Instant.now().plus(6, ChronoUnit.HOURS), "FREE");
        Patient holder = patient();
        String appointment = book(holder, slot);

        HttpResponse<String> response = cancel(holder.browser(), appointment);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
        Map<String, Object> body = body(response);
        assertThat(body.get("code")).isEqualTo("CANCEL_DEADLINE_PASSED");
        assertThat(body.get("message"))
                .isEqualTo("Ce rendez-vous ne peut plus être annulé moins de 12 heures avant son début.");
        assertThat(jdbc.queryForObject("select status from appointments where id = ?::uuid", String.class, appointment))
                .isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject("select status from slots where id = ?", String.class, slot)).isEqualTo("BOOKED");
    }

    /** Cancelling twice changes nothing: not the date of the first cancel, not the slot someone booked since. */
    @Test
    void anAlreadyCancelledAppointmentStaysAsItWas() throws Exception {
        UUID slot = insertSlot(insertPractitioner("Nadia", "Haddad", insertSpecialty("Médecine générale (deux fois)")),
                insertClinic("Clinique Annulation Bis"), LATER, "FREE");
        Patient holder = patient();
        String appointment = book(holder, slot);
        assertThat(cancel(holder.browser(), appointment).statusCode()).isEqualTo(204);
        Object firstCancel = jdbc.queryForObject("select cancelled_at from appointments where id = ?::uuid", Object.class, appointment);
        book(patient(), slot);

        HttpResponse<String> again = cancel(holder.browser(), appointment);

        assertThat(again.statusCode()).as(again.body()).isEqualTo(204);
        assertThat(jdbc.queryForObject("select cancelled_at from appointments where id = ?::uuid", Object.class, appointment))
                .isEqualTo(firstCancel);
        assertThat(jdbc.queryForObject("select status from slots where id = ?", String.class, slot)).isEqualTo("BOOKED");
    }

    @Test
    void anAnonymousVisitorIsAskedToLogIn() throws Exception {
        assertThat(cancel(new Browser(), UUID.randomUUID().toString()).statusCode()).isEqualTo(401);
    }

    /** The story says only the holder: administrative staff do not cancel here, even with an unreadable id. */
    @Test
    void aPractitionerAndStaffAreRefused() throws Exception {
        String appointment = book(patient(), insertSlot(
                insertPractitioner("Hela", "Mansour", insertSpecialty("Dermatologie (annulation)")),
                insertClinic("Clinique Annulation Staff"), LATER, "FREE"));
        Browser practitioner = new Browser().login("practitioner.dev@medlink.local", DEV_USERS_PASSWORD);
        Browser staff = new Browser().login("staff.dev@medlink.local", DEV_USERS_PASSWORD);

        for (String id : List.of(appointment, "abc")) {
            assertThat(cancel(practitioner, id).statusCode()).as("practitioner " + id).isEqualTo(403);
            assertThat(cancel(staff, id).statusCode()).as("staff " + id).isEqualTo(403);
        }
        assertThat(jdbc.queryForObject("select status from appointments where id = ?::uuid", String.class, appointment))
                .isEqualTo("BOOKED");
    }

    @Test
    void withoutTheCsrfHeaderTheCancelIsRefused() throws Exception {
        UUID slot = insertSlot(insertPractitioner("Sami", "Trabelsi", insertSpecialty("Cardiologie (CSRF annulation)")),
                insertClinic("Clinique Annulation CSRF"), LATER, "FREE");
        Patient holder = patient();
        String appointment = book(holder, slot);

        HttpResponse<String> response = holder.browser().post("/api/appointments/" + appointment + "/cancel", "", false);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("select status from appointments where id = ?::uuid", String.class, appointment))
                .isEqualTo("BOOKED");
    }

    private static HttpResponse<String> cancel(Browser browser, String appointment) throws Exception {
        return browser.postJson("/api/appointments/" + appointment + "/cancel", "");
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
    private static List<Object> slotIds(Map<String, Object> search) {
        return ((List<Map<String, Object>>) search.get("items")).stream().map(item -> item.get("slotId")).toList();
    }

    /** A logged-in patient as registration leaves them: a users row (the login) and a patients row. */
    record Patient(UUID id, Browser browser) {}

    private Patient patient() throws Exception {
        String email = uniqueEmail("cancel");
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
