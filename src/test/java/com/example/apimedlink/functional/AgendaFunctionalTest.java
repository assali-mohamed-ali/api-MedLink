package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * MLink-26 (story 26, "Agenda multi-sites"), through the real API: a practitioner sees their slots of the three
 * clinics in one answer, opens and closes slots, is refused an overlap in any clinic, and nobody else can touch
 * their slots. Written first (red), then made green by 26.3 to 26.6. Each test creates its own practitioners and
 * patients, so the other tests' rows never show in its agenda. The three clinics are the real ones of V15.
 */
class AgendaFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Agenda-Functional-2026!";

    static final UUID SOUSSE = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    static final UUID SFAX = UUID.fromString("c1000000-0000-4000-8000-000000000002");
    static final UUID TUNIS = UUID.fromString("c1000000-0000-4000-8000-000000000003");

    /** In 10 days at 09:00 UTC: far enough from the other tests' dates and from the clock shifts. */
    static final Instant DAY = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(10, ChronoUnit.DAYS)
            .plus(9, ChronoUnit.HOURS);

    static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    final KeycloakConsole console = new KeycloakConsole();
    final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    JdbcTemplate jdbc;

    /** A logged-in practitioner: their id (Keycloak id = users.id), their login and their browser. */
    record Pro(UUID id, String email, Browser browser) {}

    // ---- 1. the agenda -------------------------------------------------------------------------

    @Test
    void theAgendaGathersTheSlotsOfTheThreeClinicsInOneAnswerEarliestFirst() throws Exception {
        Pro pro = practitioner(SOUSSE, SFAX, TUNIS);
        UUID inTunis = insertSlot(pro.id(), TUNIS, DAY.plus(2, ChronoUnit.HOURS), "FREE");
        UUID inSousse = insertSlot(pro.id(), SOUSSE, DAY, "FREE");
        UUID inSfax = insertSlot(pro.id(), SFAX, DAY.plus(1, ChronoUnit.HOURS), "BOOKED");

        HttpResponse<String> response = agenda(pro.browser(), DAY.minus(1, ChronoUnit.DAYS), DAY.plus(1, ChronoUnit.DAYS));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        Map<String, Object> body = body(response);
        assertThat(items(body)).extracting(item -> item.get("slotId"))
                .containsExactly(inSousse.toString(), inSfax.toString(), inTunis.toString());
        assertThat(items(body)).extracting(item -> clinicName(item))
                .containsExactly("Clinique MedLink Sousse", "Clinique MedLink Sfax", "Clinique MedLink Tunis");
        assertThat(items(body)).extracting(item -> item.get("status")).containsExactly("OPEN", "BOOKED", "OPEN");
        // The clinics the practitioner works in come with the answer, by name, whatever the week holds
        assertThat(clinics(body)).extracting(clinic -> clinic.get("name"))
                .containsExactly("Clinique MedLink Sfax", "Clinique MedLink Sousse", "Clinique MedLink Tunis");
        Map<String, Object> first = items(body).get(0);
        assertThat(first.get("startsAt")).isEqualTo(DAY.toString());
        assertThat(first.get("endsAt")).isEqualTo(DAY.plus(30, ChronoUnit.MINUTES).toString());
    }

    @Test
    void theAgendaShowsOnlyTheCallersSlotsEvenInAnEmptyWeek() throws Exception {
        Pro mine = practitioner(SOUSSE);
        Pro other = practitioner(SOUSSE);
        insertSlot(other.id(), SOUSSE, DAY, "FREE");

        Map<String, Object> body = body(agenda(mine.browser(), DAY.minus(1, ChronoUnit.DAYS), DAY.plus(1, ChronoUnit.DAYS)));

        assertThat(items(body)).isEmpty();
        assertThat(clinics(body)).hasSize(1);
    }

    @Test
    void theAgendaIncludesTheSlotsAlreadyPassedOfThePeriod() throws Exception {
        Pro pro = practitioner(SOUSSE);
        Instant yesterday = Instant.now().minus(1, ChronoUnit.DAYS);
        UUID past = insertSlot(pro.id(), SOUSSE, yesterday, "BOOKED");

        Map<String, Object> body = body(agenda(pro.browser(), yesterday.minus(1, ChronoUnit.HOURS), yesterday.plus(2, ChronoUnit.HOURS)));

        assertThat(items(body)).extracting(item -> item.get("slotId")).containsExactly(past.toString());
    }

    @Test
    void aBookedSlotShowsItsPatientsNamesAndNothingMoreAndACancelledAppointmentIsNotShown() throws Exception {
        Pro pro = practitioner(SOUSSE);
        UUID patient = insertPatient("Amel", "Ben Salah");
        UUID booked = insertSlot(pro.id(), SOUSSE, DAY, "BOOKED");
        UUID appointment = insertAppointment(booked, patient, "BOOKED");
        UUID freedAgain = insertSlot(pro.id(), SOUSSE, DAY.plus(1, ChronoUnit.HOURS), "FREE");
        insertAppointment(freedAgain, patient, "CANCELLED");

        HttpResponse<String> response = agenda(pro.browser(), DAY.minus(1, ChronoUnit.HOURS), DAY.plus(3, ChronoUnit.HOURS));

        List<Map<String, Object>> items = items(body(response));
        assertThat(items.get(0).get("appointment")).isEqualTo(Map.of("id", appointment.toString(), "status", "BOOKED",
                "patient", Map.of("id", patient.toString(), "firstName", "Amel", "lastName", "Ben Salah")));
        assertThat(items.get(1)).as("cancelled: the slot is open again").doesNotContainKey("appointment");
        assertThat(items.get(1).get("status")).isEqualTo("OPEN");
        // Names only: no phone, address or birth date of the patient, no email
        assertThat(response.body()).doesNotContain("1990-04-12").doesNotContain("+216").doesNotContain("rue du Test")
                .doesNotContain("@").doesNotContain("phone").doesNotContain("address").doesNotContain("birthDate");
    }

    @Test
    void anAgendaAnswerIsNeverCached() throws Exception {
        Pro pro = practitioner(SOUSSE);

        HttpResponse<String> response = agenda(pro.browser(), DAY, DAY.plus(1, ChronoUnit.DAYS));

        assertThat(response.headers().allValues("Cache-Control")).anyMatch(value -> value.contains("no-store"));
    }

    @Test
    void theAgendaRefusesMissingOrBrokenDatesWithTheFieldsNamed() throws Exception {
        Browser browser = practitioner(SOUSSE).browser();

        Map<String, Object> missing = body(browser.call("/api/practitioner/agenda"));
        assertThat(details(missing)).containsOnlyKeys("from", "to");
        assertThat(details(body(agenda(browser, DAY, DAY.minus(1, ChronoUnit.DAYS))))).containsKey("to");
        assertThat(details(body(agenda(browser, DAY, DAY.plus(32, ChronoUnit.DAYS))))).containsKey("to");
        HttpResponse<String> unreadable = browser.call("/api/practitioner/agenda?from=demain&to=" + DAY);
        assertThat(unreadable.statusCode()).isEqualTo(400);
        assertThat(details(body(unreadable))).containsKey("from");
    }

    @Test
    void aPractitionerAccountWithoutAProfileGetsAClearConflictNotAnEmptyAgenda() throws Exception {
        String email = uniqueEmail("agenda-noprofile");
        console.createUser(email, PASSWORD, "/practitioners");
        Browser browser = new Browser().login(email, PASSWORD);

        HttpResponse<String> response = agenda(browser, DAY, DAY.plus(1, ChronoUnit.DAYS));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(body(response).get("code")).isEqualTo("PRACTITIONER_PROFILE_MISSING");
    }

    // ---- 2. opening slots, overlaps --------------------------------------------------------------

    @Test
    void anOpenedSlotIsSavedAndAnotherSessionSeesItInTheAgenda() throws Exception {
        Pro pro = practitioner(SOUSSE, TUNIS);

        HttpResponse<String> created = open(pro.browser(), TUNIS, DAY, DAY.plus(30, ChronoUnit.MINUTES));

        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        Map<String, Object> slot = body(created);
        assertThat(slot.get("status")).isEqualTo("OPEN");
        assertThat(clinicName(slot)).isEqualTo("Clinique MedLink Tunis");
        // A brand new session (as after a restart): the slot is in the database and in the agenda
        Browser again = new Browser().login(pro.email(), PASSWORD);
        assertThat(items(body(agenda(again, DAY.minus(1, ChronoUnit.HOURS), DAY.plus(1, ChronoUnit.DAYS)))))
                .extracting(item -> item.get("slotId")).containsExactly(slot.get("slotId"));
        assertThat(jdbc.queryForObject("select status from slots where id = ?", String.class, UUID.fromString((String) slot.get("slotId"))))
                .isEqualTo("FREE");
        assertThat(audit(pro.id())).containsExactly("SLOT_OPENED/SUCCESS");
    }

    @Test
    void aSlotOverlappingAnotherOfTheSamePractitionerInAnotherClinicIsRefusedAndNothingIsAdded() throws Exception {
        Pro pro = practitioner(SOUSSE, TUNIS);
        Instant nine = DAY;
        assertThat(open(pro.browser(), SOUSSE, nine, nine.plus(1, ChronoUnit.HOURS)).statusCode()).isEqualTo(201);

        HttpResponse<String> refused = open(pro.browser(), TUNIS, nine.plus(30, ChronoUnit.MINUTES), nine.plus(90, ChronoUnit.MINUTES));

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        Map<String, Object> error = body(refused);
        assertThat(error.get("code")).isEqualTo("SLOT_OVERLAP");
        // The message names the clinic and the time already taken, in Tunis time
        assertThat((String) error.get("message")).contains("Clinique MedLink Sousse")
                .contains(TIME.format(nine.atZone(TUNIS_ZONE))).contains(TIME.format(nine.plus(1, ChronoUnit.HOURS).atZone(TUNIS_ZONE)));
        assertThat(details(error)).containsEntry("conflictClinic", "Clinique MedLink Sousse")
                .containsEntry("conflictStartsAt", nine.toString()).containsEntry("conflictStatus", "OPEN");
        assertThat(slotCount(pro.id())).isEqualTo(1);
    }

    @Test
    void backToBackSlotsAreAcceptedAndASameClinicOverlapOrSameStartIsRefused() throws Exception {
        Pro pro = practitioner(SOUSSE, TUNIS);
        assertThat(open(pro.browser(), SOUSSE, DAY, DAY.plus(1, ChronoUnit.HOURS)).statusCode()).isEqualTo(201);

        // The end is exclusive: 10:00 after 09:00-10:00 is accepted, in another clinic
        assertThat(open(pro.browser(), TUNIS, DAY.plus(1, ChronoUnit.HOURS), DAY.plus(2, ChronoUnit.HOURS)).statusCode()).isEqualTo(201);
        // Same clinic overlap, and the same start with another end: refused, never a 500
        assertThat(open(pro.browser(), SOUSSE, DAY.plus(90, ChronoUnit.MINUTES), DAY.plus(150, ChronoUnit.MINUTES)).statusCode()).isEqualTo(409);
        HttpResponse<String> sameStart = open(pro.browser(), SOUSSE, DAY, DAY.plus(15, ChronoUnit.MINUTES));
        assertThat(sameStart.statusCode()).as(sameStart.body()).isEqualTo(409);
        assertThat(body(sameStart).get("code")).isEqualTo("SLOT_OVERLAP");
        assertThat(slotCount(pro.id())).isEqualTo(2);
    }

    @Test
    void twoPractitionersMayHaveSlotsAtTheSameTime() throws Exception {
        Pro a = practitioner(SOUSSE);
        Pro b = practitioner(SOUSSE);

        assertThat(open(a.browser(), SOUSSE, DAY, DAY.plus(30, ChronoUnit.MINUTES)).statusCode()).isEqualTo(201);
        assertThat(open(b.browser(), SOUSSE, DAY, DAY.plus(30, ChronoUnit.MINUTES)).statusCode()).isEqualTo(201);
    }

    @Test
    void twoSimultaneousOverlappingOpeningsLeaveExactlyOneSlotAndNoServerError() throws Exception {
        Pro pro = practitioner(SOUSSE, TUNIS);
        Browser second = new Browser().login(pro.email(), PASSWORD);
        CountDownLatch go = new CountDownLatch(1);
        CompletableFuture<HttpResponse<String>> one = CompletableFuture.supplyAsync(() -> after(go,
                () -> open(pro.browser(), SOUSSE, DAY, DAY.plus(1, ChronoUnit.HOURS))));
        CompletableFuture<HttpResponse<String>> two = CompletableFuture.supplyAsync(() -> after(go,
                () -> open(second, TUNIS, DAY.plus(30, ChronoUnit.MINUTES), DAY.plus(90, ChronoUnit.MINUTES))));
        go.countDown();

        List<Integer> statuses = List.of(one.get().statusCode(), two.get().statusCode()).stream().sorted().toList();

        assertThat(statuses).as("one 201 and one 409, never a 500").containsExactly(201, 409);
        assertThat(slotCount(pro.id())).isEqualTo(1);
    }

    @Test
    void openingIsRefusedWhenTheInputBreaksARuleAndNothingIsAdded() throws Exception {
        Pro pro = practitioner(SOUSSE);
        UUID notMine = TUNIS;

        assertThat(details(body(open(pro.browser(), SOUSSE, DAY.plus(1, ChronoUnit.HOURS), DAY)))).containsKey("endsAt");
        assertThat(details(body(open(pro.browser(), SOUSSE, DAY, DAY.plus(2, ChronoUnit.MINUTES))))).containsKey("endsAt");
        assertThat(details(body(open(pro.browser(), SOUSSE, DAY, DAY.plus(9, ChronoUnit.HOURS))))).containsKey("endsAt");
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        assertThat(details(body(open(pro.browser(), SOUSSE, past, past.plus(30, ChronoUnit.MINUTES))))).containsKey("startsAt");
        Instant farAway = Instant.now().plus(400, ChronoUnit.DAYS);
        assertThat(details(body(open(pro.browser(), SOUSSE, farAway, farAway.plus(30, ChronoUnit.MINUTES))))).containsKey("startsAt");
        // A clinic where the practitioner does not work, and one that does not exist: the same answer
        assertThat(details(body(open(pro.browser(), notMine, DAY, DAY.plus(30, ChronoUnit.MINUTES))))).containsKey("clinicId");
        assertThat(details(body(open(pro.browser(), UUID.randomUUID(), DAY, DAY.plus(30, ChronoUnit.MINUTES))))).containsKey("clinicId");
        // Missing fields
        HttpResponse<String> empty = pro.browser().postJson("/api/practitioner/slots", "{}");
        assertThat(empty.statusCode()).isEqualTo(400);
        assertThat(details(body(empty))).containsOnlyKeys("clinicId", "startsAt", "endsAt");
        assertThat(slotCount(pro.id())).isZero();
    }

    // ---- 3. closing and reopening ----------------------------------------------------------------

    @Test
    void aClosedSlotStaysClosedInTheNextSessionBlocksItsTimeAndCanBeReopened() throws Exception {
        Pro pro = practitioner(SOUSSE, TUNIS);
        UUID slot = openAndGetId(pro, SOUSSE, DAY);

        HttpResponse<String> closed = patch(pro.browser(), slot, "{\"status\":\"CLOSED\"}");

        assertThat(closed.statusCode()).as(closed.body()).isEqualTo(200);
        assertThat(body(closed).get("status")).isEqualTo("CLOSED");
        Browser again = new Browser().login(pro.email(), PASSWORD);
        assertThat(items(body(agenda(again, DAY.minus(1, ChronoUnit.HOURS), DAY.plus(1, ChronoUnit.DAYS)))))
                .extracting(item -> item.get("status")).containsExactly("CLOSED");
        assertThat(jdbc.queryForObject("select status from slots where id = ?", String.class, slot)).isEqualTo("CLOSED");
        // A closed slot keeps its time: another clinic cannot take it, and the answer says it is closed
        Map<String, Object> blocked = body(open(pro.browser(), TUNIS, DAY, DAY.plus(30, ChronoUnit.MINUTES)));
        assertThat(blocked.get("code")).isEqualTo("SLOT_OVERLAP");
        assertThat(details(blocked)).containsEntry("conflictStatus", "CLOSED");
        // Closing again is accepted and writes nothing more; reopening frees the slot for patients again
        assertThat(patch(pro.browser(), slot, "{\"status\":\"CLOSED\"}").statusCode()).isEqualTo(200);
        HttpResponse<String> reopened = patch(pro.browser(), slot, "{\"status\":\"OPEN\"}");
        assertThat(body(reopened).get("status")).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("select status from slots where id = ?", String.class, slot)).isEqualTo("FREE");
        assertThat(audit(pro.id())).containsExactly("SLOT_OPENED/SUCCESS", "SLOT_CLOSED/SUCCESS", "SLOT_OPENED/SUCCESS");
    }

    @Test
    void aClosedSlotIsNeverOfferedToPatients() throws Exception {
        Pro pro = practitioner(SOUSSE);
        UUID open = insertSlot(pro.id(), SOUSSE, DAY, "FREE");
        insertSlot(pro.id(), SOUSSE, DAY.plus(1, ChronoUnit.HOURS), "CLOSED");
        String email = uniqueEmail("agenda-patient");
        console.createUser(email, PASSWORD);
        Browser patient = new Browser();
        assertThat(patient.apiLogin(email, PASSWORD).statusCode()).isEqualTo(200);

        Map<String, Object> search = body(patient.call("/api/slots?practitionerId=" + pro.id() + "&from=" + DAY.minus(1, ChronoUnit.DAYS)
                + "&to=" + DAY.plus(1, ChronoUnit.DAYS)));

        assertThat(items(search)).extracting(item -> item.get("slotId")).containsExactly(open.toString());
    }

    @Test
    void aClosedSlotCannotBeBookedEvenByItsIdAndNoAppointmentIsCreated() throws Exception {
        Pro pro = practitioner(SOUSSE);
        UUID closed = openAndGetId(pro, SOUSSE, DAY.plus(3, ChronoUnit.HOURS));
        assertThat(patch(pro.browser(), closed, "{\"status\":\"CLOSED\"}").statusCode()).isEqualTo(200);
        // A patient with a profile (the booking needs one), who knows the slot's id without the search
        String email = uniqueEmail("agenda-patient");
        UUID patientId = console.createUser(email, PASSWORD);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?) on conflict (id) do nothing", patientId, email, email);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Test', ?)",
                patientId, Date.valueOf("1990-04-12"));
        Browser patient = new Browser();
        assertThat(patient.apiLogin(email, PASSWORD).statusCode()).isEqualTo(200);

        HttpResponse<String> booking = patient.postJson("/api/appointments", "{\"slotId\":\"" + closed + "\"}");

        assertThat(booking.statusCode()).as(booking.body()).isEqualTo(409);
        assertThat(body(booking).get("code")).isEqualTo("SLOT_TAKEN");
        assertThat(jdbc.queryForObject("select count(*) from appointments where slot_id = ?", Integer.class, closed)).isZero();
        assertThat(jdbc.queryForObject("select status from slots where id = ?", String.class, closed)).isEqualTo("CLOSED");
    }

    @Test
    void aBookedSlotCannotBeClosedAndStaysUnchanged() throws Exception {
        Pro pro = practitioner(SOUSSE);
        UUID patient = insertPatient("Amel", "Ben Salah");
        UUID booked = insertSlot(pro.id(), SOUSSE, DAY, "BOOKED");
        insertAppointment(booked, patient, "BOOKED");
        // Even a slot still marked free is protected by its active appointment
        UUID inconsistent = insertSlot(pro.id(), SOUSSE, DAY.plus(1, ChronoUnit.HOURS), "FREE");
        insertAppointment(inconsistent, patient, "BOOKED");

        for (UUID slot : List.of(booked, inconsistent)) {
            HttpResponse<String> response = patch(pro.browser(), slot, "{\"status\":\"CLOSED\"}");
            assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
            assertThat(body(response).get("code")).isEqualTo("SLOT_BOOKED");
        }
        assertThat(jdbc.queryForObject("select status from slots where id = ?", String.class, booked)).isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject("select status from slots where id = ?", String.class, inconsistent)).isEqualTo("FREE");
        assertThat(audit(pro.id())).isEmpty();
    }

    @Test
    void aSlotAlreadyStartedCannotBeReopened() throws Exception {
        Pro pro = practitioner(SOUSSE);
        UUID past = insertSlot(pro.id(), SOUSSE, Instant.now().minus(1, ChronoUnit.HOURS), "CLOSED");

        HttpResponse<String> response = patch(pro.browser(), past, "{\"status\":\"OPEN\"}");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(body(response).get("code")).isEqualTo("SLOT_IN_PAST");
    }

    @Test
    void onlyOpenAndClosedAreAcceptedAsAStatusAndTheSlotIsUnchanged() throws Exception {
        Pro pro = practitioner(SOUSSE);
        UUID slot = insertSlot(pro.id(), SOUSSE, DAY, "FREE");

        for (String body : List.of("{\"status\":\"BOOKED\"}", "{\"status\":\"FREE\"}", "{\"status\":\"closed\"}", "{}")) {
            HttpResponse<String> response = patch(pro.browser(), slot, body);
            assertThat(response.statusCode()).as(body).isEqualTo(400);
            assertThat(details(body(response))).as(body).containsKey("status");
        }
        assertThat(pro.browser().http.send(rawPatch(pro.browser(), "not-an-id", "{\"status\":\"CLOSED\"}"),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select status from slots where id = ?", String.class, slot)).isEqualTo("FREE");
    }

    // ---- 4. nobody else touches my slots -------------------------------------------------------

    @Test
    void anotherPractitionerCannotChangeMySlotAndTheRefusalIsTheSameAsForASlotThatDoesNotExist() throws Exception {
        Pro owner = practitioner(SOUSSE);
        Pro intruder = practitioner(SOUSSE);
        UUID slot = insertSlot(owner.id(), SOUSSE, DAY, "FREE");

        HttpResponse<String> mine = patch(intruder.browser(), slot, "{\"status\":\"CLOSED\"}");
        HttpResponse<String> nobodys = patch(intruder.browser(), UUID.randomUUID(), "{\"status\":\"CLOSED\"}");

        assertThat(mine.statusCode()).isEqualTo(403);
        assertThat(nobodys.statusCode()).isEqualTo(403);
        Map<String, Object> a = body(mine);
        Map<String, Object> b = body(nobodys);
        assertThat(a.get("code")).isEqualTo(b.get("code"));
        assertThat(a.get("message")).isEqualTo(b.get("message"));
        assertThat(jdbc.queryForObject("select status from slots where id = ?", String.class, slot)).isEqualTo("FREE");
        // The refusal is in the audit trail, for the intruder
        assertThat(audit(intruder.id())).contains("ACCESS_DENIED/DENIED");
        assertThat(audit(owner.id())).isEmpty();
    }

    @Test
    void patientsAndStaffAreRefusedOnEveryRouteEvenWithAnEmptyBodyOrNoDates() throws Exception {
        String patientEmail = uniqueEmail("agenda-patient");
        console.createUser(patientEmail, PASSWORD);
        Browser patient = new Browser();
        assertThat(patient.apiLogin(patientEmail, PASSWORD).statusCode()).isEqualTo(200);
        String staffEmail = uniqueEmail("agenda-staff");
        console.createUser(staffEmail, PASSWORD, "/administrative-staff");
        Browser staff = new Browser().login(staffEmail, PASSWORD);

        for (Browser who : List.of(patient, staff)) {
            // 403, not 400: a role that may not use the route never learns its validation rules
            assertThat(who.call("/api/practitioner/agenda").statusCode()).isEqualTo(403);
            assertThat(who.postJson("/api/practitioner/slots", "{}").statusCode()).isEqualTo(403);
            assertThat(patch(who, UUID.randomUUID(), "{}").statusCode()).isEqualTo(403);
        }
        assertThat(new Browser().call("/api/practitioner/agenda").statusCode()).isEqualTo(401);
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** A practitioner made in the Keycloak console, with a profile and their clinics, logged in. */
    private Pro practitioner(UUID... clinics) throws Exception {
        String email = uniqueEmail("agenda-pro");
        UUID id = console.createUser(email, PASSWORD, "/practitioners");
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        // The login finds this row by id (and follows the email): the practitioner row hangs on it
        jdbc.update("insert into users (id, username, email) values (?, ?, ?) on conflict (id) do nothing", id, email, email);
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Sami', 'Dev', ?)", id, specialty);
        for (UUID clinic : clinics) {
            jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", id, clinic);
        }
        return new Pro(id, email, new Browser().login(email, PASSWORD));
    }

    private HttpResponse<String> agenda(Browser browser, Instant from, Instant to) throws Exception {
        return browser.call("/api/practitioner/agenda?from=" + from + "&to=" + to);
    }

    private HttpResponse<String> open(Browser browser, UUID clinic, Instant start, Instant end) throws Exception {
        return browser.postJson("/api/practitioner/slots",
                "{\"clinicId\":\"" + clinic + "\",\"startsAt\":\"" + start + "\",\"endsAt\":\"" + end + "\"}");
    }

    private UUID openAndGetId(Pro pro, UUID clinic, Instant start) throws Exception {
        HttpResponse<String> created = open(pro.browser(), clinic, start, start.plus(30, ChronoUnit.MINUTES));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString((String) body(created).get("slotId"));
    }

    /** PATCH as React sends it, with the CSRF header ({@link Browser} only posts). */
    private HttpResponse<String> patch(Browser browser, UUID slot, String body) throws Exception {
        return browser.http.send(rawPatch(browser, slot.toString(), body), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest rawPatch(Browser browser, String id, String body) throws Exception {
        if (browser.cookies.value("XSRF-TOKEN") == null) {
            browser.call("/api/auth/csrf");
        }
        return HttpRequest.newBuilder(URI.create(appUrl("/api/practitioner/slots/" + id)))
                .header("X-Forwarded-For", browser.forwardedFor)
                .header("X-XSRF-TOKEN", browser.cookies.value("XSRF-TOKEN"))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static HttpResponse<String> after(CountDownLatch go, ThrowingSupplier<HttpResponse<String>> call) {
        try {
            go.await();
            return call.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 499);
        return json.readValue(response.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> clinics(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("clinics");
    }

    @SuppressWarnings("unchecked")
    private static String clinicName(Map<String, Object> item) {
        return (String) ((Map<String, Object>) item.get("clinic")).get("name");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(Map<String, Object> error) {
        return (Map<String, Object>) error.get("details");
    }

    private int slotCount(UUID practitioner) {
        return jdbc.queryForObject("select count(*) from slots where practitioner_id = ?", Integer.class, practitioner);
    }

    /**
     * What the audit trail holds for this user about slots and refusals, oldest first, as {@code ACTION/OUTCOME}.
     * Other tasks audit other actions (logins, accounts): they must not break these tests.
     */
    private List<String> audit(UUID user) {
        return jdbc.queryForList("select action || '/' || outcome from audit_entries where user_id = ? "
                + "and action in ('SLOT_OPENED', 'SLOT_CLOSED', 'ACCESS_DENIED') order by timestamp, id", String.class, user);
    }

    // ---- test data, written straight to the database --------------------------------------------

    private UUID insertSlot(UUID practitioner, UUID clinic, Instant start, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, ?)",
                id, practitioner, clinic, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)), status);
        return id;
    }

    private UUID insertPatient(String firstName, String lastName) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", id, "patient-" + id, "patient-" + id + "@example.com");
        jdbc.update("insert into patients (id, first_name, last_name, birth_date, phone, address) values (?, ?, ?, ?, '+21620000000', '1 rue du Test')",
                id, firstName, lastName, Date.valueOf("1990-04-12"));
        return id;
    }

    private UUID insertAppointment(UUID slot, UUID patient, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into appointments (id, slot_id, patient_id, booked_by, status) values (?, ?, ?, ?, ?)",
                id, slot, patient, patient, status);
        return id;
    }
}
