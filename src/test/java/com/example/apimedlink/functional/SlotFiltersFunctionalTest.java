package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.text.Collator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * MLink-163 (story MLink-25): the lists of the slot search form. A patient gets the clinics, the specialties and the
 * practitioners with the clinics each one works in, so the page offers lists instead of ids to type and shows only
 * the practitioners of the chosen clinic. Nothing about a practitioner but their names, specialty and clinics.
 */
class SlotFiltersFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Slot-Filters-2026!";

    final KeycloakConsole console = new KeycloakConsole();
    final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aPatientGetsTheClinicsTheSpecialtiesAndThePractitionersWithTheirClinics() throws Exception {
        UUID specialty = insertSpecialty("Néphrologie (filtres)");
        UUID sousse = insertClinic("Clinique Filtres Sousse", "Sousse");
        UUID tunis = insertClinic("Clinique Filtres Tunis", "Tunis");
        UUID practitioner = insertPractitioner("Rim", "Jaziri", specialty);
        worksIn(practitioner, sousse);
        worksIn(practitioner, tunis);

        HttpResponse<String> response = patient().call("/api/slots/filters");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        Map<String, Object> body = body(response);
        assertThat(list(body, "clinics"))
                .contains(Map.of("id", sousse.toString(), "name", "Clinique Filtres Sousse", "city", "Sousse"))
                .contains(Map.of("id", tunis.toString(), "name", "Clinique Filtres Tunis", "city", "Tunis"));
        assertThat(list(body, "specialties"))
                .contains(Map.of("id", specialty.toString(), "name", "Néphrologie (filtres)"));
        Map<String, Object> found = list(body, "practitioners").stream()
                .filter(item -> practitioner.toString().equals(item.get("id")))
                .findFirst().orElseThrow();
        assertThat(found).containsEntry("firstName", "Rim").containsEntry("lastName", "Jaziri")
                .containsEntry("specialtyId", specialty.toString());
        assertThat(found.get("clinicIds")).asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactlyInAnyOrder(sousse.toString(), tunis.toString());
        // Nothing more about a practitioner than the form needs: no email, no username
        assertThat(found).containsOnlyKeys("id", "firstName", "lastName", "specialtyId", "clinicIds");
        assertThat(response.body()).doesNotContain("@");
    }

    /** In French order, as a reader expects it: "Pédiatrie" comes before "Pneumologie", not after "Z". */
    @Test
    void theListsAreSortedByNameInFrenchOrder() throws Exception {
        insertClinic("Clinique Filtres B", "Sfax");
        insertClinic("Clinique Filtres A", "Sfax");
        insertSpecialty("Pneumologie (tri)");
        insertSpecialty("Pédiatrie (tri)");
        Collator french = Collator.getInstance(Locale.FRENCH);

        Map<String, Object> body = body(patient().call("/api/slots/filters"));

        List<String> clinicNames = names(list(body, "clinics"));
        List<String> specialtyNames = names(list(body, "specialties"));
        assertThat(clinicNames).isSortedAccordingTo(french::compare)
                .containsSubsequence("Clinique Filtres A", "Clinique Filtres B");
        assertThat(specialtyNames).isSortedAccordingTo(french::compare)
                .containsSubsequence("Pédiatrie (tri)", "Pneumologie (tri)");
    }

    private static List<String> names(List<Map<String, Object>> items) {
        return items.stream().map(item -> (String) item.get("name")).toList();
    }

    @Test
    void anAnonymousVisitorIsAskedToLogIn() throws Exception {
        assertThat(new Browser().call("/api/slots/filters").statusCode()).isEqualTo(401);
    }

    @Test
    void aPractitionerAndStaffAreRefused() throws Exception {
        Browser practitioner = new Browser().login("practitioner.dev@medlink.local", DEV_USERS_PASSWORD);
        Browser staff = new Browser().login("staff.dev@medlink.local", DEV_USERS_PASSWORD);

        assertThat(practitioner.call("/api/slots/filters").statusCode()).isEqualTo(403);
        assertThat(staff.call("/api/slots/filters").statusCode()).isEqualTo(403);
    }

    /** A patient account made in the console (group /patients), logged in through our login endpoint. */
    private Browser patient() throws Exception {
        String email = uniqueEmail("slot-filters");
        console.createUser(email, PASSWORD);
        Browser browser = new Browser();
        assertThat(browser.apiLogin(email, PASSWORD).statusCode()).as("patient logged in").isEqualTo(200);
        return browser;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readValue(response.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> body, String name) {
        assertThat(body).as("the " + name + " list").containsKey(name);
        return (List<Map<String, Object>>) body.get(name);
    }

    // ---- test data, written straight to the database ---------------------------------------------

    /** Specialty names are unique: each test uses its own. */
    private UUID insertSpecialty(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", id, name);
        return id;
    }

    private UUID insertClinic(String name, String city) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, ?, ?)", id, name, city);
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

    private void worksIn(UUID practitioner, UUID clinic) {
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", practitioner, clinic);
    }
}
