package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
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
 * MLink-98 (story MLink-25): a patient searches the free slots, by clinic, practitioner or specialty and
 * dates, through the real API. Only free slots that start in the future come back, earliest first,
 * paged; a practitioner, staff and anonymous callers are refused. Each test creates its own
 * practitioners, clinics and specialties, so the other tests' rows never match its filters.
 */
class SlotSearchFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Slot-Search-2026!";

    /** Tomorrow 09:00 UTC: every slot of a test is placed from there. */
    static final Instant DAY = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS)
            .plus(9, ChronoUnit.HOURS);

    final KeycloakConsole console = new KeycloakConsole();
    final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aPatientGetsOnlyTheFreeSlotsOfThePeriodThatStartInTheFutureEarliestFirst() throws Exception {
        UUID specialty = insertSpecialty("Cardiologie (période)");
        UUID practitioner = insertPractitioner("Sami", "Trabelsi", specialty);
        UUID clinic = insertClinic("Clinique Sousse");
        UUID later = insertSlot(practitioner, clinic, DAY.plus(2, ChronoUnit.DAYS), "FREE");
        UUID sooner = insertSlot(practitioner, clinic, DAY, "FREE");
        insertSlot(practitioner, clinic, DAY.plus(1, ChronoUnit.DAYS), "BOOKED");
        insertSlot(practitioner, clinic, DAY.plus(20, ChronoUnit.DAYS), "FREE"); // after the period
        insertSlot(practitioner, clinic, Instant.now().minus(1, ChronoUnit.DAYS), "FREE"); // in the past

        // "from" in the past: the search starts now, a past slot never comes back
        HttpResponse<String> response = patient().call("/api/slots?practitionerId=" + practitioner
                + "&from=" + Instant.now().minus(2, ChronoUnit.DAYS) + "&to=" + DAY.plus(10, ChronoUnit.DAYS));

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> body = body(response);
        assertThat(slotIds(body)).containsExactly(sooner.toString(), later.toString());
        assertThat(((Number) body.get("total")).longValue()).isEqualTo(2);

        Map<String, Object> first = items(body).get(0);
        assertThat(first.get("startsAt")).isEqualTo(DAY.toString());
        assertThat(first.get("endsAt")).isEqualTo(DAY.plus(30, ChronoUnit.MINUTES).toString());
        assertThat(first.get("clinic")).isEqualTo(Map.of("id", clinic.toString(), "name", "Clinique Sousse"));
        assertThat(first.get("practitioner"))
                .isEqualTo(Map.of("id", practitioner.toString(), "firstName", "Sami", "lastName", "Trabelsi"));
        assertThat(first.get("specialty")).isEqualTo("Cardiologie (période)");
        // Nothing more about the practitioner than their names: no email, no username
        assertThat(response.body()).doesNotContain("@");
    }

    @Test
    void eachFilterNarrowsTheSearch() throws Exception {
        UUID cardiology = insertSpecialty("Cardiologie (filtres)");
        UUID pediatrics = insertSpecialty("Pédiatrie (filtres)");
        UUID cardiologist = insertPractitioner("Leila", "Ben Ali", cardiology);
        UUID pediatrician = insertPractitioner("Omar", "Gharbi", pediatrics);
        UUID sfax = insertClinic("Clinique Sfax");
        UUID tunis = insertClinic("Clinique Tunis");
        UUID cardiologyInSfax = insertSlot(cardiologist, sfax, DAY, "FREE");
        UUID pediatricsInTunis = insertSlot(pediatrician, tunis, DAY, "FREE");
        UUID cardiologyInTunis = insertSlot(cardiologist, tunis, DAY.plus(1, ChronoUnit.DAYS), "FREE");
        Browser patient = patient();
        String period = "&from=" + DAY.minus(1, ChronoUnit.HOURS) + "&to=" + DAY.plus(5, ChronoUnit.DAYS);

        assertThat(slotIds(body(patient.call("/api/slots?clinicId=" + sfax + period))))
                .containsExactly(cardiologyInSfax.toString());
        assertThat(slotIds(body(patient.call("/api/slots?specialtyId=" + pediatrics + period))))
                .containsExactly(pediatricsInTunis.toString());
        assertThat(slotIds(body(patient.call("/api/slots?practitionerId=" + cardiologist + "&clinicId=" + tunis + period))))
                .containsExactly(cardiologyInTunis.toString());
        // Filters combine: the pediatrician never works in Sfax
        Map<String, Object> none = body(patient.call("/api/slots?practitionerId=" + pediatrician + "&clinicId=" + sfax + period));
        assertThat(items(none)).isEmpty();
        assertThat(((Number) none.get("total")).longValue()).isZero();
    }

    @Test
    void theResultsArePaged() throws Exception {
        UUID practitioner = insertPractitioner("Nadia", "Haddad", insertSpecialty("Médecine générale (pages)"));
        UUID clinic = insertClinic("Clinique Tunis");
        UUID first = insertSlot(practitioner, clinic, DAY, "FREE");
        UUID second = insertSlot(practitioner, clinic, DAY.plus(30, ChronoUnit.MINUTES), "FREE");
        UUID third = insertSlot(practitioner, clinic, DAY.plus(60, ChronoUnit.MINUTES), "FREE");
        Browser patient = patient();
        String search = "/api/slots?practitionerId=" + practitioner + "&from=" + DAY.minus(1, ChronoUnit.HOURS)
                + "&to=" + DAY.plus(1, ChronoUnit.DAYS) + "&size=2";

        Map<String, Object> page0 = body(patient.call(search + "&page=0"));
        Map<String, Object> page1 = body(patient.call(search + "&page=1"));

        assertThat(slotIds(page0)).containsExactly(first.toString(), second.toString());
        assertThat(((Number) page0.get("total")).longValue()).isEqualTo(3);
        assertThat(slotIds(page1)).containsExactly(third.toString());
    }

    @Test
    void theDatesAreRequired() throws Exception {
        HttpResponse<String> response = patient().call("/api/slots?to=" + DAY.plus(1, ChronoUnit.DAYS));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(body(response).get("code")).isEqualTo("VALIDATION_FAILED");
        assertThat(response.body()).contains("\"from\"");
    }

    // ---- MLink-100: wrong input is refused clearly, an empty search says so ---------------------------

    @Test
    void theEndMustComeAfterTheStart() throws Exception {
        Browser patient = patient();

        HttpResponse<String> same = patient.call("/api/slots?from=" + DAY + "&to=" + DAY);
        HttpResponse<String> reversed = patient.call("/api/slots?from=" + DAY.plus(1, ChronoUnit.DAYS) + "&to=" + DAY);

        assertRefused(same, "to", "La date de fin doit être après la date de début.");
        assertRefused(reversed, "to", "La date de fin doit être après la date de début.");
    }

    @Test
    void thePeriodIsThirtyOneDaysAtMost() throws Exception {
        Browser patient = patient();
        Instant end = DAY.plus(31, ChronoUnit.DAYS);

        HttpResponse<String> exactly = patient.call("/api/slots?from=" + DAY + "&to=" + end);
        HttpResponse<String> longer = patient.call("/api/slots?from=" + DAY + "&to=" + end.plusSeconds(1));

        assertThat(exactly.statusCode()).as("31 days exactly").isEqualTo(200);
        assertRefused(longer, "to", "La période ne peut pas dépasser 31 jours.");
    }

    @Test
    void unreadableIdsAndDatesAreNamedInOurErrorBody() throws Exception {
        Browser patient = patient();

        assertRefused(patient.call(searchTomorrow() + "&clinicId=abc"), "clinicId", "Identifiant invalide.");
        assertRefused(patient.call(searchTomorrow() + "&specialtyId=abc"), "specialtyId", "Identifiant invalide.");
        assertRefused(patient.call("/api/slots?from=demain&to=" + DAY), "from",
                "Date invalide (format attendu : 2026-10-27T09:00:00Z).");
    }

    @Test
    void pagesStartAtZeroAndHoldOneToFiftySlots() throws Exception {
        Browser patient = patient();

        assertRefused(patient.call(searchTomorrow() + "&size=0"), "size", "La taille de page doit être comprise entre 1 et 50.");
        assertRefused(patient.call(searchTomorrow() + "&size=51"), "size", "La taille de page doit être comprise entre 1 et 50.");
        assertRefused(patient.call(searchTomorrow() + "&page=-1"), "page", "Le numéro de page ne peut pas être négatif.");
        assertThat(patient.call(searchTomorrow() + "&size=50").statusCode()).isEqualTo(200);
        assertThat(patient.call(searchTomorrow() + "&size=1").statusCode()).isEqualTo(200);
    }

    @Test
    void anEmptySearchSuggestsWideningItAndOnlyThen() throws Exception {
        UUID practitioner = insertPractitioner("Hela", "Mansour", insertSpecialty("Dermatologie (aucun résultat)"));
        UUID clinic = insertClinic("Clinique Sfax");
        insertSlot(practitioner, clinic, DAY.plus(3, ChronoUnit.DAYS), "FREE");
        Browser patient = patient();

        Map<String, Object> empty = body(patient.call("/api/slots?practitionerId=" + practitioner
                + "&from=" + DAY + "&to=" + DAY.plus(1, ChronoUnit.DAYS)));
        Map<String, Object> found = body(patient.call("/api/slots?practitionerId=" + practitioner
                + "&from=" + DAY + "&to=" + DAY.plus(5, ChronoUnit.DAYS)));

        assertThat(items(empty)).isEmpty();
        assertThat(((Number) empty.get("total")).longValue()).isZero();
        assertThat(empty.get("message"))
                .isEqualTo("Aucun créneau disponible. Élargissez la recherche (dates, clinique ou spécialité).");
        assertThat(items(found)).hasSize(1);
        assertThat(found).doesNotContainKey("message");
    }

    /** A 400 in our error body: code VALIDATION_FAILED and the French message under the field's name. */
    private void assertRefused(HttpResponse<String> response, String field, String message) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        Map<String, Object> body = body(response);
        assertThat(body.get("code")).isEqualTo("VALIDATION_FAILED");
        assertThat(body.get("details")).as("details").isEqualTo(Map.of(field, message));
    }

    @Test
    void anAnonymousVisitorIsAskedToLogIn() throws Exception {
        HttpResponse<String> response = new Browser().call(searchTomorrow());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void aPractitionerAndStaffAreRefused() throws Exception {
        Browser practitioner = new Browser().login("practitioner.dev@medlink.local", DEV_USERS_PASSWORD);
        Browser staff = new Browser().login("staff.dev@medlink.local", DEV_USERS_PASSWORD);

        assertThat(practitioner.call(searchTomorrow()).statusCode()).isEqualTo(403);
        assertThat(staff.call(searchTomorrow()).statusCode()).isEqualTo(403);
    }

    private static String searchTomorrow() {
        return "/api/slots?from=" + DAY + "&to=" + DAY.plus(1, ChronoUnit.DAYS);
    }

    /** A patient account made in the console (group /patients), logged in through our login endpoint. */
    private Browser patient() throws Exception {
        String email = uniqueEmail("slots");
        console.createUser(email, PASSWORD);
        Browser browser = new Browser();
        assertThat(browser.apiLogin(email, PASSWORD).statusCode()).as("patient logged in").isEqualTo(200);
        return browser;
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

    private static List<Object> slotIds(Map<String, Object> body) {
        return items(body).stream().map(item -> item.get("slotId")).toList();
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

    /** The practitioner works in the clinic (a slot elsewhere is refused by the schema), then the slot. */
    private UUID insertSlot(UUID practitioner, UUID clinic, Instant start, String status) {
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?) on conflict do nothing",
                practitioner, clinic);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, ?)",
                id, practitioner, clinic, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)), status);
        return id;
    }
}
