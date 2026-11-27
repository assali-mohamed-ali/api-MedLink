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
 * MLink-167 (story MLink-27): a patient cannot hold two appointments at the same time, even in two clinics (a gap
 * found in a demo: two appointments the same day at 08:00, in Sousse and in Sfax). A booking or a move onto a slot
 * that overlaps one of the patient's booked appointments is 409 PATIENT_BUSY; a cancelled appointment and, for a move,
 * the appointment being moved do not count.
 */
class PatientOverlapFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Patient-Overlap-2026!";
    static final String BUSY = "Vous avez déjà un rendez-vous à ce moment-là.";

    /** Three days ahead, 09:00 UTC: past the 12-hour cancel deadline, so a cancel or a move is allowed. */
    static final Instant NINE = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(3, ChronoUnit.DAYS).plus(9, ChronoUnit.HOURS);

    final KeycloakConsole console = new KeycloakConsole();
    final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theSameTimeInAnotherClinicIsRefusedAndTheSecondSlotStaysFree() throws Exception {
        Patient patient = patient();
        UUID inSousse = slot("Sousse", NINE, 30);
        UUID inSfax = slot("Sfax", NINE, 30);
        assertThat(book(patient.browser(), inSousse).statusCode()).isEqualTo(201);

        HttpResponse<String> second = book(patient.browser(), inSfax);

        assertThat(second.statusCode()).as(second.body()).isEqualTo(409);
        assertThat(body(second).get("code")).isEqualTo("PATIENT_BUSY");
        assertThat(body(second).get("message")).isEqualTo(BUSY);
        assertThat(slotStatus(inSfax)).isEqualTo("FREE");
        assertThat(booked(patient.id())).isEqualTo(1);
    }

    @Test
    void aSlotThatOnlyPartlyOverlapsIsRefusedToo() throws Exception {
        Patient patient = patient();
        assertThat(book(patient.browser(), slot("Partiel A", NINE, 30)).statusCode()).isEqualTo(201);

        HttpResponse<String> overlapping = book(patient.browser(), slot("Partiel B", NINE.plus(15, ChronoUnit.MINUTES), 30));

        assertThat(overlapping.statusCode()).as(overlapping.body()).isEqualTo(409);
        assertThat(body(overlapping).get("code")).isEqualTo("PATIENT_BUSY");
    }

    @Test
    void backToBackSlotsAndAnotherPatientAtTheSameTimeAreFine() throws Exception {
        Patient patient = patient();
        assertThat(book(patient.browser(), slot("Suite A", NINE, 30)).statusCode()).isEqualTo(201);

        assertThat(book(patient.browser(), slot("Suite B", NINE.plus(30, ChronoUnit.MINUTES), 30)).statusCode())
                .as("starts when the first one ends").isEqualTo(201);
        assertThat(book(patient().browser(), slot("Suite C", NINE, 30)).statusCode())
                .as("the rule is per patient").isEqualTo(201);
    }

    @Test
    void aCancelledAppointmentDoesNotBlockItsTime() throws Exception {
        Patient patient = patient();
        String first = id(book(patient.browser(), slot("Annulé A", NINE, 30)));
        assertThat(patient.browser().postJson("/api/appointments/" + first + "/cancel", "").statusCode()).isEqualTo(204);

        assertThat(book(patient.browser(), slot("Annulé B", NINE, 30)).statusCode()).isEqualTo(201);
    }

    @Test
    void aMoveOntoAnotherAppointmentsTimeIsRefusedAndTheOldOneIsKept() throws Exception {
        Patient patient = patient();
        UUID original = slot("Report A", NINE, 30);
        String toMove = id(book(patient.browser(), original));
        assertThat(book(patient.browser(), slot("Report B", NINE.plus(2, ChronoUnit.HOURS), 30)).statusCode()).isEqualTo(201);
        UUID target = slotOfSamePractitioner(original, NINE.plus(2, ChronoUnit.HOURS));

        HttpResponse<String> move = move(patient.browser(), toMove, target);

        assertThat(move.statusCode()).as(move.body()).isEqualTo(409);
        assertThat(body(move).get("code")).isEqualTo("PATIENT_BUSY");
        assertThat(appointmentStatus(toMove)).isEqualTo("BOOKED");
        assertThat(slotStatus(target)).isEqualTo("FREE");
    }

    /** US-10: changing practitioner requires the separate staff reassignment workflow. */
    @Test
    void aMoveToAnotherPractitionerIsRefusedAndTheOriginalIsKept() throws Exception {
        Patient patient = patient();
        String toMove = id(book(patient.browser(), slot("Décalage A", NINE, 30)));

        HttpResponse<String> move = move(patient.browser(), toMove, slot("Décalage B", NINE.plus(15, ChronoUnit.MINUTES), 30));

        assertThat(move.statusCode()).as(move.body()).isEqualTo(409);
        assertThat(body(move).get("code")).isEqualTo("DIFFERENT_PRACTITIONER");
        assertThat(appointmentStatus(toMove)).isEqualTo("BOOKED");
    }

    /** Two sessions of one patient booking two overlapping slots at the same instant: one appointment, never two. */
    @Test
    void twoOverlappingBookingsOfOnePatientAtTheSameInstantGiveOneAppointment() throws Exception {
        Patient patient = patient();
        Browser other = new Browser();
        assertThat(other.apiLogin(patient.email(), PASSWORD).statusCode()).isEqualTo(200);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                Instant start = NINE.plus(3L + round, ChronoUnit.HOURS);
                List<UUID> slots = List.of(slot("Course A" + round, start, 30), slot("Course B" + round, start, 30));
                CountDownLatch go = new CountDownLatch(1);
                List<Future<HttpResponse<String>>> bookings = new ArrayList<>();
                List<Browser> sessions = List.of(patient.browser(), other);
                for (int i = 0; i < 2; i++) {
                    Browser session = sessions.get(i);
                    UUID slot = slots.get(i);
                    bookings.add(pool.submit(() -> {
                        go.await();
                        return book(session, slot);
                    }));
                }

                go.countDown();
                List<Integer> statuses = new ArrayList<>();
                for (Future<HttpResponse<String>> booking : bookings) {
                    statuses.add(booking.get().statusCode());
                }

                assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(201, 409);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(booked(patient.id())).isEqualTo(5);
    }

    // ---- through the API, as the patient would ---------------------------------------------------

    private HttpResponse<String> book(Browser browser, UUID slot) throws Exception {
        return browser.postJson("/api/appointments", "{\"slotId\":\"" + slot + "\"}");
    }

    private HttpResponse<String> move(Browser browser, String appointment, UUID slot) throws Exception {
        return browser.postJson("/api/appointments/" + appointment + "/reschedule", "{\"newSlotId\":\"" + slot + "\"}");
    }

    private String id(HttpResponse<String> booking) {
        assertThat(booking.statusCode()).as(booking.body()).isEqualTo(201);
        return (String) body(booking).get("id");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpResponse<String> response) {
        return json.readValue(response.body(), Map.class);
    }

    /** A logged-in patient as registration leaves them: a users row (the login) and a patients row. */
    record Patient(UUID id, String email, Browser browser) {}

    private Patient patient() throws Exception {
        String email = uniqueEmail("overlap");
        UUID id = console.createUser(email, PASSWORD);
        Browser browser = new Browser();
        assertThat(browser.apiLogin(email, PASSWORD).statusCode()).as("patient logged in").isEqualTo(200);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Ben Salah', ?)",
                id, Date.valueOf("1990-04-12"));
        return new Patient(id, email, browser);
    }

    // ---- test data, written straight to the database ---------------------------------------------

    /** A free slot of a practitioner of its own, in a clinic of its own: slots of one practitioner never overlap. */
    private UUID slot(String name, Instant start, int minutes) {
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Chevauchement " + specialty);
        UUID practitioner = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)",
                practitioner, "practitioner-" + practitioner, "practitioner-" + practitioner + "@example.com");
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Rim', 'Jaziri', ?)",
                practitioner, specialty);
        UUID clinic = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, ?, ?)", clinic, "Clinique " + name + " " + clinic, name);
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", practitioner, clinic);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, 'FREE')",
                id, practitioner, clinic, Timestamp.from(start), Timestamp.from(start.plus(minutes, ChronoUnit.MINUTES)));
        return id;
    }

    private UUID slotOfSamePractitioner(UUID original, Instant start) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) "
                        + "select ?, practitioner_id, clinic_id, ?, ?, 'FREE' from slots where id = ?",
                id, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)), original);
        return id;
    }

    private String slotStatus(UUID slot) {
        return jdbc.queryForObject("select status from slots where id = ?", String.class, slot);
    }

    private String appointmentStatus(String appointment) {
        return jdbc.queryForObject("select status from appointments where id = ?::uuid", String.class, appointment);
    }

    private int booked(UUID patient) {
        return jdbc.queryForObject("select count(*) from appointments where patient_id = ? and status = 'BOOKED'",
                Integer.class, patient);
    }
}
