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
 * US-29: staff set the clinics of a practitioner, {@code PUT /api/admin/practitioners/{id}/clinics}. The list replaces
 * the current one; a clinic that still holds the practitioner's slots cannot be removed (409); unknown ids give 404;
 * practitioners, patients and anonymous callers are refused. NOT RUN in the session that wrote it (no Docker).
 */
class PractitionerClinicFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Practitioner-Clinics-2026!";

    final KeycloakConsole console = new KeycloakConsole();
    final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void staffReplaceTheClinicsOfAPractitionerAndItIsAudited() throws Exception {
        Browser staff = staff();
        UUID first = insertClinic("Clinique US29 A");
        UUID second = insertClinic("Clinique US29 B");
        UUID practitioner = insertPractitioner();
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", practitioner, first);

        HttpResponse<String> response = staff.putJson(path(practitioner), ids(second));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(jdbc.queryForList("select clinic_id from practitioner_clinics where practitioner_id = ?", UUID.class,
                practitioner)).containsExactly(second);
        assertThat(clinicNames(response)).containsExactly("Clinique US29 B");
        assertThat(jdbc.queryForList("select outcome from audit_entries where action = 'ACCOUNT_UPDATED' "
                + "and resource_type = 'PRACTITIONER_CLINICS' and resource_id = ?", String.class, practitioner.toString()))
                .containsExactly("SUCCESS");
    }

    @Test
    void aClinicHoldingTheirSlotsCannotBeRemoved() throws Exception {
        Browser staff = staff();
        UUID kept = insertClinic("Clinique US29 C");
        UUID busy = insertClinic("Clinique US29 D");
        UUID practitioner = insertPractitioner();
        for (UUID clinic : List.of(kept, busy)) {
            jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", practitioner, clinic);
        }
        Instant start = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, 'FREE')",
                UUID.randomUUID(), practitioner, busy, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)));

        HttpResponse<String> response = staff.putJson(path(practitioner), ids(kept));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        assertThat(map(response)).containsEntry("code", "CLINIC_HAS_SLOTS");
        assertThat(jdbc.queryForList("select clinic_id from practitioner_clinics where practitioner_id = ?", UUID.class,
                practitioner)).containsExactlyInAnyOrder(kept, busy);
    }

    @Test
    void anUnknownPractitionerOrClinicIsNotFoundAndAnEmptyListIsInvalid() throws Exception {
        Browser staff = staff();
        UUID clinic = insertClinic("Clinique US29 E");
        UUID practitioner = insertPractitioner();

        assertThat(staff.putJson(path(UUID.randomUUID()), ids(clinic)).statusCode()).isEqualTo(404);
        assertThat(staff.putJson(path(practitioner), ids(UUID.randomUUID())).statusCode()).isEqualTo(404);
        assertThat(staff.putJson(path(practitioner), "{\"clinicIds\": []}").statusCode()).isEqualTo(400);
    }

    @Test
    void practitionersPatientsAndAnonymousCallersAreRefused() throws Exception {
        UUID clinic = insertClinic("Clinique US29 F");
        UUID practitioner = insertPractitioner();
        String proEmail = email("clinics-pro");
        console.createUser(proEmail, PASSWORD, "/practitioners");
        Browser pro = new Browser().login(proEmail, PASSWORD);
        String patientEmail = email("clinics-patient");
        console.createUser(patientEmail, PASSWORD);
        Browser patient = new Browser();
        assertThat(patient.apiLogin(patientEmail, PASSWORD).statusCode()).isEqualTo(200);

        assertThat(pro.putJson(path(practitioner), ids(clinic)).statusCode()).isEqualTo(403);
        assertThat(patient.putJson(path(practitioner), ids(clinic)).statusCode()).isEqualTo(403);
        assertThat(new Browser().putJson(path(practitioner), ids(clinic)).statusCode()).isIn(401, 403);
        assertThat(jdbc.queryForObject("select count(*) from practitioner_clinics where practitioner_id = ?",
                Integer.class, practitioner)).isZero();
    }

    private Browser staff() throws Exception {
        String email = email("clinics-staff");
        console.createUser(email, PASSWORD, "/administrative-staff");
        return new Browser().login(email, PASSWORD);
    }

    private static String path(UUID practitioner) {
        return "/api/admin/practitioners/" + practitioner + "/clinics";
    }

    private static String ids(UUID... clinics) {
        StringBuilder body = new StringBuilder("{\"clinicIds\": [");
        for (int i = 0; i < clinics.length; i++) {
            body.append(i == 0 ? "" : ", ").append('"').append(clinics[i]).append('"');
        }
        return body.append("]}").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(HttpResponse<String> response) {
        return mapper.readValue(response.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<String> clinicNames(HttpResponse<String> response) {
        List<Map<String, Object>> clinics = (List<Map<String, Object>>) map(response).get("clinics");
        return clinics.stream().map(clinic -> (String) clinic.get("name")).toList();
    }

    private UUID insertClinic(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, ?, ?)", id, name, name);
        return id;
    }

    private UUID insertPractitioner() {
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)",
                id, "practitioner-" + id, "practitioner-" + id + "@example.com");
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Sami', 'Clinique', ?)",
                id, specialty);
        return id;
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
