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
 * MLink-102 (story MLink-27): a patient books a free slot online. The appointment always belongs to the caller
 * (never to an id from the body), the slot leaves the search, and a taken, past or unknown slot is refused. Only a
 * patient with a patient profile (made at registration) can book; anyone else is refused before the body is read.
 */
class AppointmentBookingFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Booking-Test-2026!";

    /** Tomorrow 09:00 UTC: the slots of a test start from there. */
    static final Instant DAY = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS)
            .plus(9, ChronoUnit.HOURS);

    final KeycloakConsole console = new KeycloakConsole();
    final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aPatientBooksAFreeSlotAndItLeavesTheSearch() throws Exception {
        UUID practitioner = insertPractitioner("Rim", "Jaziri", insertSpecialty("Cardiologie (réservation)"));
        UUID clinic = insertClinic("Clinique Réservation Sfax");
        UUID slot = insertSlot(practitioner, clinic, DAY, "FREE");
        Patient patient = patient();

        HttpResponse<String> response = patient.browser().postJson("/api/appointments", "{\"slotId\":\"" + slot + "\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        Map<String, Object> body = body(response);
        assertThat(body.get("status")).isEqualTo("BOOKED");
        assertThat(body.get("clinic")).isEqualTo(Map.of("id", clinic.toString(), "name", "Clinique Réservation Sfax"));
        assertThat(body.get("practitioner"))
                .isEqualTo(Map.of("id", practitioner.toString(), "firstName", "Rim", "lastName", "Jaziri"));
        assertThat(body.get("startsAt")).isEqualTo(DAY.toString());
        assertThat(body.get("endsAt")).isEqualTo(DAY.plus(30, ChronoUnit.MINUTES).toString());
        UUID appointment = UUID.fromString((String) body.get("id"));

        // In the database: one BOOKED appointment of this patient, booked by them, and the slot is taken
        assertThat(jdbc.queryForMap("select patient_id, booked_by, status from appointments where id = ?", appointment))
                .containsEntry("patient_id", patient.id()).containsEntry("booked_by", patient.id())
                .containsEntry("status", "BOOKED");
        assertThat(jdbc.queryForObject("select status from slots where id = ?", String.class, slot)).isEqualTo("BOOKED");
        // ... so the search no longer offers it
        Map<String, Object> search = body(patient.browser().call("/api/slots?practitionerId=" + practitioner
                + "&from=" + DAY.minus(1, ChronoUnit.HOURS) + "&to=" + DAY.plus(1, ChronoUnit.DAYS)));
        assertThat((List<?>) search.get("items")).isEmpty();
    }

    @Test
    void anIdInTheBodyIsIgnoredTheAppointmentBelongsToTheCaller() throws Exception {
        UUID slot = insertSlot(insertPractitioner("Sami", "Trabelsi", insertSpecialty("Cardiologie (titulaire)")),
                insertClinic("Clinique Réservation Tunis"), DAY, "FREE");
        Patient caller = patient();
        Patient someoneElse = patient();

        HttpResponse<String> response = caller.browser().postJson("/api/appointments",
                "{\"slotId\":\"" + slot + "\",\"patientId\":\"" + someoneElse.id() + "\",\"bookedBy\":\"" + someoneElse.id() + "\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(jdbc.queryForMap("select patient_id, booked_by from appointments where slot_id = ?", slot))
                .containsEntry("patient_id", caller.id()).containsEntry("booked_by", caller.id());
    }

    @Test
    void aTakenSlotIsRefusedAndTheFirstBookingStays() throws Exception {
        UUID slot = insertSlot(insertPractitioner("Leila", "Ben Ali", insertSpecialty("Cardiologie (déjà pris)")),
                insertClinic("Clinique Réservation Sousse"), DAY, "FREE");
        Patient first = patient();
        Patient second = patient();
        assertThat(first.browser().postJson("/api/appointments", "{\"slotId\":\"" + slot + "\"}").statusCode())
                .isEqualTo(201);

        HttpResponse<String> response = second.browser().postJson("/api/appointments", "{\"slotId\":\"" + slot + "\"}");

        assertSlotTaken(response);
        assertThat(jdbc.queryForObject("select count(*) from appointments where slot_id = ?", Integer.class, slot))
                .isEqualTo(1);
    }

    @Test
    void aPastSlotIsRefused() throws Exception {
        UUID slot = insertSlot(insertPractitioner("Omar", "Gharbi", insertSpecialty("Pédiatrie (passé)")),
                insertClinic("Clinique Réservation Passé"), Instant.now().minus(2, ChronoUnit.HOURS), "FREE");

        assertSlotTaken(patient().browser().postJson("/api/appointments", "{\"slotId\":\"" + slot + "\"}"));
        assertThat(jdbc.queryForObject("select count(*) from appointments where slot_id = ?", Integer.class, slot))
                .isZero();
    }

    @Test
    void anUnknownSlotIsNotFound() throws Exception {
        HttpResponse<String> response =
                patient().browser().postJson("/api/appointments", "{\"slotId\":\"" + UUID.randomUUID() + "\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
        assertThat(body(response).get("code")).isEqualTo("NOT_FOUND");
    }

    @Test
    void aMissingOrUnreadableSlotIdIsNamedInOurErrorBody() throws Exception {
        Browser patient = patient().browser();

        assertRefused(patient.postJson("/api/appointments", "{}"), "Le créneau est obligatoire.");
        assertRefused(patient.postJson("/api/appointments", "{\"slotId\":\"abc\"}"), "Identifiant invalide.");
    }

    @Test
    void aPatientWithoutAPatientProfileIsToldToCompleteIt() throws Exception {
        UUID slot = insertSlot(insertPractitioner("Nadia", "Haddad", insertSpecialty("Médecine générale (profil)")),
                insertClinic("Clinique Réservation Profil"), DAY, "FREE");
        // Made in the console, never registered: a users row after login, but no patients row
        String email = uniqueEmail("booking-no-profile");
        console.createUser(email, PASSWORD);
        Browser browser = new Browser();
        assertThat(browser.apiLogin(email, PASSWORD).statusCode()).isEqualTo(200);

        HttpResponse<String> response = browser.postJson("/api/appointments", "{\"slotId\":\"" + slot + "\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
        Map<String, Object> body = body(response);
        assertThat(body.get("code")).isEqualTo("PATIENT_PROFILE_REQUIRED");
        assertThat(body.get("message")).isEqualTo("Complétez votre profil patient avant de réserver.");
        assertThat(jdbc.queryForObject("select status from slots where id = ?", String.class, slot)).isEqualTo("FREE");
    }

    @Test
    void anAnonymousVisitorIsAskedToLogIn() throws Exception {
        HttpResponse<String> response =
                new Browser().postJson("/api/appointments", "{\"slotId\":\"" + UUID.randomUUID() + "\"}");

        assertThat(response.statusCode()).isEqualTo(401);
    }

    /** The role is checked before the body is read: a non-patient never gets the patient's 400 (review on #87). */
    @Test
    void aPractitionerAndStaffAreRefusedEvenWithAnUnreadableBody() throws Exception {
        Browser practitioner = new Browser().login("practitioner.dev@medlink.local", DEV_USERS_PASSWORD);
        Browser staff = new Browser().login("staff.dev@medlink.local", DEV_USERS_PASSWORD);

        for (String body : List.of("{\"slotId\":\"" + UUID.randomUUID() + "\"}", "{\"slotId\":\"abc\"}", "{}")) {
            assertThat(practitioner.postJson("/api/appointments", body).statusCode()).as("practitioner " + body)
                    .isEqualTo(403);
            assertThat(staff.postJson("/api/appointments", body).statusCode()).as("staff " + body).isEqualTo(403);
        }
    }

    @Test
    void withoutTheCsrfHeaderTheBookingIsRefused() throws Exception {
        UUID slot = insertSlot(insertPractitioner("Hela", "Mansour", insertSpecialty("Dermatologie (CSRF)")),
                insertClinic("Clinique Réservation CSRF"), DAY, "FREE");
        Browser patient = patient().browser();
        patient.call("/api/auth/csrf");

        HttpResponse<String> response = patient.post("/api/appointments", "{\"slotId\":\"" + slot + "\"}", false);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("select status from slots where id = ?", String.class, slot)).isEqualTo("FREE");
    }

    private void assertSlotTaken(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        Map<String, Object> body = body(response);
        assertThat(body.get("code")).isEqualTo("SLOT_TAKEN");
        assertThat(body.get("message")).isEqualTo("Ce créneau n'est plus disponible. Choisissez-en un autre.");
    }

    private void assertRefused(HttpResponse<String> response, String message) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        Map<String, Object> body = body(response);
        assertThat(body.get("code")).isEqualTo("VALIDATION_FAILED");
        assertThat(body.get("details")).isEqualTo(Map.of("slotId", message));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpResponse<String> response) {
        return json.readValue(response.body(), Map.class);
    }

    /** A logged-in patient as registration leaves them: a users row (the login) and a patients row. */
    record Patient(UUID id, Browser browser) {}

    private Patient patient() throws Exception {
        String email = uniqueEmail("booking");
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
