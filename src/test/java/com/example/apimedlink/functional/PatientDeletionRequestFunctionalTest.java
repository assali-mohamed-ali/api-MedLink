package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Date;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * US-16 (request part): a patient asks for their data to be deleted, reads the state, and takes the request back. The
 * request deletes nothing (the purge is not implemented until the retention period is confirmed); asking twice keeps
 * the first request; every change is audited. Practitioners, staff and anonymous callers are refused. NOT RUN in the
 * session that wrote it (no Docker).
 */
class PatientDeletionRequestFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Patient-Deletion-2026!";
    static final String PATH = "/api/patients/me/deletion-request";

    final KeycloakConsole console = new KeycloakConsole();
    final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @SuppressWarnings("unchecked")
    void aPatientAsksReadsAndCancelsAndNothingIsDeleted() throws Exception {
        String email = email("deletion-patient");
        UUID id = console.createUser(email, PASSWORD);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?) on conflict (id) do nothing", id, email, email);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Ben Salah', ?)",
                id, Date.valueOf("1990-04-12"));
        Browser patient = new Browser();
        assertThat(patient.apiLogin(email, PASSWORD).statusCode()).isEqualTo(200);

        Map<String, Object> none = mapper.readValue(patient.call(PATH).body(), Map.class);
        assertThat(none).containsEntry("requested", false);

        HttpResponse<String> asked = patient.postJson(PATH, "");
        assertThat(asked.statusCode()).as(asked.body()).isEqualTo(200);
        Map<String, Object> first = mapper.readValue(asked.body(), Map.class);
        assertThat(first).containsEntry("requested", true);
        assertThat(first.get("requestedAt")).isNotNull();
        assertThat(first.get("scheduledFor")).isNotNull();

        Map<String, Object> again = mapper.readValue(patient.postJson(PATH, "").body(), Map.class);
        assertThat(again.get("requestedAt")).as("the first request is kept").isEqualTo(first.get("requestedAt"));
        assertThat(jdbc.queryForObject("select count(*) from audit_entries where action = 'ACCOUNT_UPDATED' "
                + "and resource_type = 'PATIENT_DELETION_REQUEST' and user_id = ?", Integer.class, id))
                .as("asked twice, audited once").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from patients where id = ?", Integer.class, id))
                .as("nothing is deleted by the request").isEqualTo(1);
        assertThat(patient.call("/api/users/_health").statusCode()).as("the account still works").isEqualTo(200);

        HttpResponse<String> cancelled = patient.delete(PATH);
        assertThat(cancelled.statusCode()).isEqualTo(204);
        assertThat(mapper.readValue(patient.call(PATH).body(), Map.class)).containsEntry("requested", false);
        assertThat(jdbc.queryForObject("select count(*) from deletion_requests where patient_id = ?", Integer.class, id))
                .isZero();
    }

    @Test
    void practitionersStaffAndAnonymousCallersAreRefused() throws Exception {
        String proEmail = email("deletion-pro");
        console.createUser(proEmail, PASSWORD, "/practitioners");
        String staffEmail = email("deletion-staff");
        console.createUser(staffEmail, PASSWORD, "/administrative-staff");

        for (Browser other : new Browser[] {new Browser().login(proEmail, PASSWORD), new Browser().login(staffEmail, PASSWORD)}) {
            assertThat(other.call(PATH).statusCode()).isEqualTo(403);
            assertThat(other.postJson(PATH, "").statusCode()).isEqualTo(403);
            assertThat(other.delete(PATH).statusCode()).isEqualTo(403);
        }
        assertThat(new Browser().call(PATH).statusCode()).isEqualTo(401);
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
