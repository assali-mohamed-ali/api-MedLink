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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * MLink-37 (37.3): a practitioner lists their unread alerts and marks them read, through the real API. Only their own:
 * another practitioner's alert, an unknown one or a malformed id all get the same 403. The contract is the one the
 * bell of the front was built on (37.5): { id, type, message, appointmentId, createdAt }, and 204 on read.
 * Alerts are inserted in SQL here: 37.2 creates them when an appointment is cancelled.
 */
class AlertFunctionalTest extends FunctionalTestBase {

    /** The test slots, one per hour from in three days: never two at once for one practitioner. */
    static final Instant FIRST_SLOT = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(3, ChronoUnit.DAYS);
    static final AtomicInteger NEXT_SLOT = new AtomicInteger();

    static final String PASSWORD = "Alert-Functional-2026!";

    final KeycloakConsole console = new KeycloakConsole();
    final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    JdbcTemplate jdbc;

    record Pro(UUID id, String email, Browser browser) {}

    // ---- 37.8: the three acceptance criteria of MLink-37, end to end ----

    /**
     * The whole story (H-06): the practitioner is logged out, a patient cancels, the practitioner gets the alert and
     * the mail; logged in again, the alert is still there, unread; once marked read it leaves the list; another
     * practitioner can neither see it nor mark it. The patient books and cancels through the real routes of US-15
     * (review on #125): the alert comes from the cancellation itself.
     */
    @Test
    void anAlertReceivedWhileLoggedOutIsThereAtTheNextLoginWithItsMailThenMarkedRead() throws Exception {
        Pro pro = practitioner();
        Pro other = practitioner();
        Patient patient = patient();
        UUID appointment = UUID.fromString(book(patient, freeSlot(pro.id())));
        assertThat(pro.browser().logout().statusCode()).isIn(204, 401);

        // The patient cancels while the practitioner is away
        HttpResponse<String> cancelled = cancel(patient, appointment.toString());
        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(204);

        // Criterion 1: an alert row for the practitioner, and the mail in Mailpit
        assertThat(jdbc.queryForObject("select count(*) from alerts where practitioner_id = ? and appointment_id = ?",
                Integer.class, pro.id(), appointment)).isEqualTo(1);
        assertThat(new Mailbox().to(pro.email(), "Un rendez-vous a été annulé")).hasSize(1);

        // Criterion 2: logged in again, the alert is still listed, unread
        Browser back = new Browser().login(pro.email(), PASSWORD);
        List<Map<String, Object>> unread = list(back.call("/api/practitioner/alerts?unread=true"));
        assertThat(unread).hasSize(1);
        assertThat(unread.get(0)).containsEntry("appointmentId", appointment.toString())
                .containsEntry("type", "APPOINTMENT_CANCELLED");
        assertThat((String) unread.get(0).get("message")).startsWith("Rendez-vous annulé : Amel Ben Salah, ");
        String alert = (String) unread.get(0).get("id");

        // Criterion 4: another practitioner cannot mark it, and does not see it
        assertThat(other.browser().postJson("/api/practitioner/alerts/" + alert + "/read", "").statusCode()).isEqualTo(403);
        assertThat(list(other.browser().call("/api/practitioner/alerts?unread=true"))).isEmpty();

        // Criterion 3: marked read by its owner, it leaves the unread list and stays read after another login
        assertThat(back.postJson("/api/practitioner/alerts/" + alert + "/read", "").statusCode()).isEqualTo(204);
        assertThat(list(back.call("/api/practitioner/alerts?unread=true"))).isEmpty();
        assertThat(list(new Browser().login(pro.email(), PASSWORD).call("/api/practitioner/alerts?unread=true"))).isEmpty();
    }

    @Test
    void aPractitionerSeesTheirUnreadAlertsNewestFirstInTheFrontsShape() throws Exception {
        Pro pro = practitioner();
        Pro other = practitioner();
        UUID appointment = appointment(pro.id());
        UUID older = insertAlert(pro.id(), appointment, "Rendez-vous annulé : Amel Ben Salah.", Instant.now().minus(2, ChronoUnit.HOURS), false);
        UUID newer = insertAlert(pro.id(), appointment(pro.id()), "Rendez-vous annulé : Youssef Trabelsi.", Instant.now().minus(1, ChronoUnit.HOURS), false);
        insertAlert(pro.id(), appointment(pro.id()), "Déjà lue.", Instant.now(), true);
        insertAlert(other.id(), appointment(other.id()), "Pas la sienne.", Instant.now(), false);

        HttpResponse<String> response = pro.browser().call("/api/practitioner/alerts?unread=true");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        List<Map<String, Object>> alerts = list(response);
        assertThat(alerts).extracting(alert -> alert.get("id")).containsExactly(newer.toString(), older.toString());
        assertThat(alerts.get(1)).containsOnlyKeys("id", "type", "message", "appointmentId", "createdAt");
        assertThat(alerts.get(1)).containsEntry("type", "APPOINTMENT_CANCELLED")
                .containsEntry("message", "Rendez-vous annulé : Amel Ben Salah.")
                .containsEntry("appointmentId", appointment.toString());
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }

    @Test
    void withoutTheParameterTheListIsTheUnreadOnesToo() throws Exception {
        Pro pro = practitioner();
        UUID unread = insertAlert(pro.id(), appointment(pro.id()), "Non lue.", Instant.now(), false);
        insertAlert(pro.id(), appointment(pro.id()), "Lue.", Instant.now(), true);

        List<Map<String, Object>> alerts = list(pro.browser().call("/api/practitioner/alerts"));

        assertThat(alerts).extracting(alert -> alert.get("id")).containsExactly(unread.toString());
    }

    @Test
    void markingAnAlertReadAnswers204AndTakesItOffTheUnreadListKeepingTheFirstReadingTime() throws Exception {
        Pro pro = practitioner();
        UUID alert = insertAlert(pro.id(), appointment(pro.id()), "Annulé.", Instant.now(), false);

        HttpResponse<String> first = pro.browser().postJson("/api/practitioner/alerts/" + alert + "/read", "");
        Timestamp readAt = jdbc.queryForObject("select read_at from alerts where id = ?", Timestamp.class, alert);
        HttpResponse<String> again = pro.browser().postJson("/api/practitioner/alerts/" + alert + "/read", "");

        assertThat(first.statusCode()).as(first.body()).isEqualTo(204);
        assertThat(again.statusCode()).as("marking twice is harmless").isEqualTo(204);
        assertThat(readAt).isNotNull();
        assertThat(jdbc.queryForObject("select read_at from alerts where id = ?", Timestamp.class, alert)).isEqualTo(readAt);
        assertThat(list(pro.browser().call("/api/practitioner/alerts?unread=true"))).isEmpty();
    }

    @Test
    void anotherPractitionersAlertAnUnknownOneOrAMalformedIdGetTheSame403AndNothingChanges() throws Exception {
        Pro owner = practitioner();
        Pro other = practitioner();
        UUID alert = insertAlert(owner.id(), appointment(owner.id()), "Annulé.", Instant.now(), false);

        List<HttpResponse<String>> refused = List.of(
                other.browser().postJson("/api/practitioner/alerts/" + alert + "/read", ""),
                other.browser().postJson("/api/practitioner/alerts/" + UUID.randomUUID() + "/read", ""),
                other.browser().postJson("/api/practitioner/alerts/not-a-uuid/read", ""));

        for (HttpResponse<String> response : refused) {
            assertThat(response.statusCode()).as(response.uri() + " " + response.body()).isEqualTo(403);
            assertThat(Browser.json(response).get("code")).isEqualTo(Browser.json(refused.get(0)).get("code"));
        }
        assertThat(jdbc.queryForObject("select read_at from alerts where id = ?", Timestamp.class, alert)).isNull();
        assertThat(list(other.browser().call("/api/practitioner/alerts"))).isEmpty();
    }

    @Test
    void patientsStaffAndAnonymousCallersAreRefused() throws Exception {
        Pro pro = practitioner();
        UUID alert = insertAlert(pro.id(), appointment(pro.id()), "Annulé.", Instant.now(), false);
        String patientEmail = email("alert-patient");
        UUID patientId = console.createUser(patientEmail, PASSWORD);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?) on conflict (id) do nothing", patientId, patientEmail, patientEmail);
        Browser patient = new Browser();
        assertThat(patient.apiLogin(patientEmail, PASSWORD).statusCode()).isEqualTo(200);
        String staffEmail = email("alert-staff");
        console.createUser(staffEmail, PASSWORD, "/administrative-staff");
        Browser staff = new Browser().login(staffEmail, PASSWORD);

        assertThat(patient.call("/api/practitioner/alerts?unread=true").statusCode()).isEqualTo(403);
        assertThat(patient.postJson("/api/practitioner/alerts/" + alert + "/read", "").statusCode()).isEqualTo(403);
        assertThat(staff.call("/api/practitioner/alerts?unread=true").statusCode()).isEqualTo(403);
        assertThat(staff.postJson("/api/practitioner/alerts/" + alert + "/read", "").statusCode()).isEqualTo(403);
        assertThat(new Browser().call("/api/practitioner/alerts?unread=true").statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select read_at from alerts where id = ?", Timestamp.class, alert)).isNull();
    }

    // ---- Review on #125: the alert comes from the real cancellation paths of US-15 ----

    /**
     * A move is not a cancellation: the practitioner of the old slot gets "Rendez-vous reporté", from the old date to the
     * new one, and a mail that says "reporté", never "annulé".
     */
    @Test
    void movingAnAppointmentTellsThePractitionerItWasMovedAndWhenToWithAMail() throws Exception {
        Pro pro = practitioner();
        Patient patient = patient();
        String old = book(patient, freeSlot(pro.id()));
        UUID newSlot = freeSlot(pro.id());

        HttpResponse<String> moved = patient.browser().postJson("/api/appointments/" + old + "/reschedule",
                "{\"newSlotId\":\"" + newSlot + "\"}");

        assertThat(moved.statusCode()).as(moved.body()).isEqualTo(200);
        List<Map<String, Object>> rows = jdbc.queryForList("select type, appointment_id, message from alerts where practitioner_id = ?", pro.id());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("type")).isEqualTo("APPOINTMENT_MOVED");
        assertThat(rows.get(0).get("appointment_id")).isEqualTo(UUID.fromString(old));
        assertThat((String) rows.get(0).get("message")).startsWith("Rendez-vous reporté : Amel Ben Salah, du ").contains(") au ");
        assertThat(new Mailbox().to(pro.email(), "Un rendez-vous a été reporté")).hasSize(1);
        assertThat(new Mailbox().to(pro.email(), "Un rendez-vous a été annulé")).isEmpty();
    }

    /** A cancel the API refuses (less than 12 hours before) changes nothing, so no alert and no mail. */
    @Test
    void aCancelRefusedByTheDeadlineGivesNoAlert() throws Exception {
        Pro pro = practitioner();
        Patient patient = patient();
        String appointment = book(patient, freeSlot(pro.id(), Instant.now().plus(6, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES)));

        HttpResponse<String> refused = cancel(patient, appointment);

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(jdbc.queryForObject("select count(*) from alerts where practitioner_id = ?", Integer.class, pro.id())).isZero();
    }

    /** Cancelling twice is one cancellation (the second changes nothing): one alert, one mail. */
    @Test
    void cancellingTwiceGivesOneAlert() throws Exception {
        Pro pro = practitioner();
        Patient patient = patient();
        String appointment = book(patient, freeSlot(pro.id()));

        assertThat(cancel(patient, appointment).statusCode()).isEqualTo(204);
        assertThat(cancel(patient, appointment).statusCode()).isEqualTo(204);

        assertThat(jdbc.queryForObject("select count(*) from alerts where practitioner_id = ?", Integer.class, pro.id()))
                .isEqualTo(1);
        assertThat(new Mailbox().to(pro.email(), "Un rendez-vous a été annulé")).hasSize(1);
    }

    // ---- Review on #125: the list is bounded (the bell asks every minute) ----

    /**
     * 25 unread alerts: the default page is the 20 newest, page 1 the 5 others; never the whole backlog in one answer.
     * Both the unread list and the full history (unread=false) are paged.
     */
    @Test
    void theListIsPagedTwentyAtATimeByDefaultNewestFirst() throws Exception {
        Pro pro = practitioner();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        List<String> newestFirst = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            newestFirst.add(0, insertAlert(pro.id(), appointment(pro.id()), "Annulé " + i + ".", now.minus(25 - i, ChronoUnit.MINUTES), false).toString());
        }

        List<Map<String, Object>> first = list(pro.browser().call("/api/practitioner/alerts"));
        List<Map<String, Object>> second = list(pro.browser().call("/api/practitioner/alerts?page=1"));
        List<Map<String, Object>> history = list(pro.browser().call("/api/practitioner/alerts?unread=false&page=0"));

        assertThat(first).extracting(alert -> alert.get("id")).containsExactlyElementsOf(newestFirst.subList(0, 20));
        assertThat(second).extracting(alert -> alert.get("id")).containsExactlyElementsOf(newestFirst.subList(20, 25));
        assertThat(history).hasSize(20);
    }

    /** Alerts created at the same instant keep one order (newest first, then by id): pages never repeat or skip one. */
    @Test
    void alertsOfTheSameInstantArePagedInOneStableOrder() throws Exception {
        Pro pro = practitioner();
        Instant same = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(insertAlert(pro.id(), appointment(pro.id()), "Même instant.", same, false).toString());
        }
        ids.sort(java.util.Comparator.reverseOrder());

        List<Object> paged = new java.util.ArrayList<>();
        for (int page = 0; page < 3; page++) {
            list(pro.browser().call("/api/practitioner/alerts?size=2&page=" + page)).forEach(alert -> paged.add(alert.get("id")));
        }

        assertThat(paged).containsExactlyElementsOf(ids);
    }

    /** 50 at most per page; a size or page out of range is a 400 naming the parameter, as on /api/admin/users. */
    @Test
    void aPageSizeAboveFiftyOrAPageOutOfRangeIsRefusedNamingTheParameter() throws Exception {
        Pro pro = practitioner();
        for (int i = 0; i < 3; i++) {
            insertAlert(pro.id(), appointment(pro.id()), "Annulé.", Instant.now(), false);
        }

        assertThat(list(pro.browser().call("/api/practitioner/alerts?size=50"))).hasSize(3);
        for (String query : List.of("size=51", "size=0", "page=-1", "size=abc")) {
            HttpResponse<String> refused = pro.browser().call("/api/practitioner/alerts?" + query);
            assertThat(refused.statusCode()).as(query + " " + refused.body()).isEqualTo(400);
            assertThat(Browser.json(refused).get("code")).isEqualTo("VALIDATION_FAILED");
            Map<?, ?> details = (Map<?, ?>) Browser.json(refused).get("details");
            assertThat(details.keySet()).as(query).hasSize(1).allMatch(key -> key.equals(query.substring(0, query.indexOf('='))));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(HttpResponse<String> response) {
        return mapper.readValue(response.body(), List.class);
    }

    private Pro practitioner() throws Exception {
        String email = email("alert-pro");
        UUID id = console.createUser(email, PASSWORD, "/practitioners");
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?) on conflict (id) do nothing", id, email, email);
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Sami', 'Dev', ?)", id, specialty);
        return new Pro(id, email, new Browser().login(email, PASSWORD));
    }

    /** A logged-in patient as registration leaves them (users row at login, patients row), as US-15's tests do. */
    record Patient(UUID id, Browser browser) {}

    private Patient patient() throws Exception {
        String email = email("alert-patient");
        UUID id = console.createUser(email, PASSWORD);
        Browser browser = new Browser();
        assertThat(browser.apiLogin(email, PASSWORD).statusCode()).as("patient logged in").isEqualTo(200);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Ben Salah', ?)",
                id, Date.valueOf("1990-04-12"));
        return new Patient(id, browser);
    }

    /** Books through the API, as the patient would; returns the appointment's id. */
    private String book(Patient patient, UUID slot) throws Exception {
        HttpResponse<String> response = patient.browser().postJson("/api/appointments", "{\"slotId\":\"" + slot + "\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return (String) mapper.readValue(response.body(), Map.class).get("id");
    }

    private static HttpResponse<String> cancel(Patient patient, String appointment) throws Exception {
        return patient.browser().postJson("/api/appointments/" + appointment + "/cancel", "");
    }

    /** A free slot of the practitioner, one hour after the previous test slot (never two at once). */
    private UUID freeSlot(UUID practitioner) {
        return freeSlot(practitioner, FIRST_SLOT.plus(NEXT_SLOT.getAndIncrement(), ChronoUnit.HOURS));
    }

    private UUID freeSlot(UUID practitioner, Instant start) {
        UUID clinic = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, ?, 'Sousse')", clinic, "Clinique " + clinic);
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", practitioner, clinic);
        UUID slot = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, 'FREE')",
                slot, practitioner, clinic, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)));
        return slot;
    }

    /** A cancelled appointment of one of the practitioner's slots. */
    private UUID appointment(UUID practitioner) {
        UUID patient = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", patient, "patient-" + patient, "patient-" + patient + "@example.com");
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Ben Salah', ?)",
                patient, Date.valueOf("1990-04-12"));
        UUID clinic = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, ?, 'Sousse')", clinic, "Clinique " + clinic);
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", practitioner, clinic);
        UUID slot = UUID.randomUUID();
        // One hour after the previous one: a practitioner's slots never overlap (the database refuses it, #116)
        Instant start = FIRST_SLOT.plus(NEXT_SLOT.getAndIncrement(), ChronoUnit.HOURS);
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, 'FREE')",
                slot, practitioner, clinic, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)));
        UUID appointment = UUID.randomUUID();
        jdbc.update("insert into appointments (id, slot_id, patient_id, booked_by, status) values (?, ?, ?, ?, 'CANCELLED')",
                appointment, slot, patient, patient);
        return appointment;
    }

    private UUID insertAlert(UUID practitioner, UUID appointment, String message, Instant createdAt, boolean read) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into alerts (id, practitioner_id, type, appointment_id, message, created_at, read_at) "
                + "values (?, ?, 'APPOINTMENT_CANCELLED', ?, ?, ?, ?)", id, practitioner, appointment, message,
                Timestamp.from(createdAt), read ? Timestamp.from(createdAt) : null);
        return id;
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
