package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * MLink-103 (story MLink-27): "if two patients choose the same slot, one appointment is made and the other is told".
 * Two patients book one slot at the same instant: one 201, one 409 SLOT_TAKEN, one BOOKED row. The slot row lock of
 * MLink-102 serves them one after the other; the unique index on the active appointment of a slot (V13) is the last
 * guard, and the API answers it with the same 409, never a 500.
 */
class AppointmentRaceFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Booking-Race-2026!";
    static final int ROUNDS = 5;

    /** Tomorrow 09:00 UTC: the slots of a test start from there. */
    static final Instant DAY = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS)
            .plus(9, ChronoUnit.HOURS);

    final KeycloakConsole console = new KeycloakConsole();
    final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void twoPatientsBookingOneSlotAtTheSameInstantGetOneAppointment() throws Exception {
        UUID practitioner = insertPractitioner("Rim", "Jaziri", insertSpecialty("Cardiologie (course)"));
        UUID clinic = insertClinic("Clinique Course Sfax");
        Browser first = patient().browser();
        Browser second = patient().browser();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                UUID slot = insertSlot(practitioner, clinic, DAY.plus(30L * round, ChronoUnit.MINUTES), "FREE");
                String body = "{\"slotId\":\"" + slot + "\"}";
                CountDownLatch start = new CountDownLatch(1);
                List<Future<HttpResponse<String>>> bookings = new ArrayList<>();
                for (Browser browser : List.of(first, second)) {
                    bookings.add(pool.submit(() -> {
                        start.await();
                        return browser.post("/api/appointments", body, true);
                    }));
                }

                start.countDown();
                List<Integer> statuses = new ArrayList<>();
                for (Future<HttpResponse<String>> booking : bookings) {
                    HttpResponse<String> response = booking.get();
                    statuses.add(response.statusCode());
                    if (response.statusCode() == 409) {
                        assertThat(body(response).get("code")).isEqualTo("SLOT_TAKEN");
                    }
                }

                assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(201, 409);
                assertThat(jdbc.queryForObject(
                        "select count(*) from appointments where slot_id = ? and status = 'BOOKED'", Integer.class, slot))
                        .as("round %d: one appointment", round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A race lost after the checks: the slot still says FREE but already holds an active appointment. Only the unique
     * index sees it; the patient must get the same 409 as for a taken slot, and nothing changes.
     */
    @Test
    void whenOnlyTheUniqueIndexCatchesTheDoubleBookingThePatientGetsSlotTakenNotAServerError() throws Exception {
        UUID slot = insertSlot(insertPractitioner("Omar", "Gharbi", insertSpecialty("Pédiatrie (dernier filet)")),
                insertClinic("Clinique Course Sousse"), DAY, "FREE");
        Patient winner = patient();
        jdbc.update("insert into appointments (id, slot_id, patient_id, booked_by, status) values (?, ?, ?, ?, 'BOOKED')",
                UUID.randomUUID(), slot, winner.id(), winner.id());
        Browser late = patient().browser();

        HttpResponse<String> response = late.postJson("/api/appointments", "{\"slotId\":\"" + slot + "\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        Map<String, Object> body = body(response);
        assertThat(body.get("code")).isEqualTo("SLOT_TAKEN");
        assertThat(body.get("message")).isEqualTo("Ce créneau n'est plus disponible. Choisissez-en un autre.");
        assertThat(jdbc.queryForObject("select count(*) from appointments where slot_id = ?", Integer.class, slot))
                .isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpResponse<String> response) {
        return json.readValue(response.body(), Map.class);
    }

    /** A logged-in patient as registration leaves them, its CSRF cookie fetched: ready to book at once. */
    record Patient(UUID id, Browser browser) {}

    private Patient patient() throws Exception {
        String email = uniqueEmail("booking-race");
        UUID id = console.createUser(email, PASSWORD);
        Browser browser = new Browser();
        assertThat(browser.apiLogin(email, PASSWORD).statusCode()).as("patient logged in").isEqualTo(200);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Ben Salah', ?)",
                id, Date.valueOf("1990-04-12"));
        browser.call("/api/auth/csrf");
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
