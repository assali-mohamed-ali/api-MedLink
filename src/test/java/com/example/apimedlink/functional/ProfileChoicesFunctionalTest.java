package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.text.Collator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * MLink-69: the lists a practitioner's profile is chosen from in the back-office ("Nouveau compte", role change).
 * Staff could not create a practitioner: the dashboard read the patients' slot search lists (/api/slots/filters),
 * which refuse staff. Staff now get the clinics and specialties only, without the practitioners of the search form.
 */
class ProfileChoicesFunctionalTest extends FunctionalTestBase {

    static final String PATH = "/api/admin/profile-choices";

    final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void staffGetTheClinicsAndTheSpecialtiesInFrenchOrder() throws Exception {
        UUID clinic = insertClinic("Clinique Choix Bizerte", "Bizerte");
        UUID specialty = insertSpecialty("Gériatrie (choix)");

        HttpResponse<String> response = staff().call(PATH);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        Map<String, Object> body = body(response);
        assertThat(body).containsOnlyKeys("clinics", "specialties");
        assertThat(list(body, "clinics"))
                .contains(Map.of("id", clinic.toString(), "name", "Clinique Choix Bizerte", "city", "Bizerte"));
        assertThat(list(body, "specialties"))
                .contains(Map.of("id", specialty.toString(), "name", "Gériatrie (choix)"))
                // The reference specialties of every environment (MLink-174)
                .anySatisfy(item -> assertThat(item).containsEntry("name", "Cardiologie"));
        Collator french = Collator.getInstance(Locale.FRENCH);
        assertThat(names(list(body, "clinics"))).isSortedAccordingTo(french::compare);
        assertThat(names(list(body, "specialties"))).isSortedAccordingTo(french::compare);
    }

    /** The point of the lists: a practitioner made from them is created (it was impossible before). */
    @Test
    void aPractitionerMadeFromTheListsIsCreated() throws Exception {
        Browser staff = staff();
        Map<String, Object> body = body(staff.call(PATH));
        String clinic = (String) list(body, "clinics").get(0).get("id");
        String specialty = (String) list(body, "specialties").get(0).get("id");
        String email = uniqueEmail("profile-choices");

        HttpResponse<String> created = staff.postJson("/api/admin/users", """
                {"email": "%s", "firstName": "Sami", "lastName": "Choix", "role": "PRACTITIONER",
                 "specialtyId": "%s", "clinicIds": ["%s"]}""".formatted(email, specialty, clinic));

        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    }

    @Test
    void aPatientAndAPractitionerAreRefused() throws Exception {
        Browser practitioner = new Browser().login("practitioner.dev@medlink.local", DEV_USERS_PASSWORD);
        Browser patient = new Browser().login("patient.dev@medlink.local", DEV_USERS_PASSWORD);

        assertThat(practitioner.call(PATH).statusCode()).isEqualTo(403);
        assertThat(patient.call(PATH).statusCode()).isEqualTo(403);
    }

    @Test
    void anAnonymousVisitorIsAskedToLogIn() throws Exception {
        assertThat(new Browser().call(PATH).statusCode()).isEqualTo(401);
    }

    private Browser staff() throws Exception {
        return new Browser().login("staff.dev@medlink.local", DEV_USERS_PASSWORD);
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

    private static List<String> names(List<Map<String, Object>> items) {
        return items.stream().map(item -> (String) item.get("name")).toList();
    }

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
}
