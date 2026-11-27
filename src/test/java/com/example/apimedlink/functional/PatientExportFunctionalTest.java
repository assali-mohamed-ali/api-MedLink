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
 * US-16 (export part): {@code GET /api/patients/me/export}, a patient downloads their own data: profile, appointments,
 * consultation reports (text opened) and the list of their documents; never another patient's; traced as DATA_READ.
 * Practitioners, staff and anonymous callers are refused. NOT RUN in the session that wrote it (no Docker).
 */
class PatientExportFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Patient-Export-2026!";
    static final Instant PAST = Instant.now().truncatedTo(ChronoUnit.DAYS).minus(2, ChronoUnit.DAYS).plus(9, ChronoUnit.HOURS);

    final KeycloakConsole console = new KeycloakConsole();
    final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @SuppressWarnings("unchecked")
    void aPatientExportsTheirOwnDataAndNobodyElses() throws Exception {
        String email = email("export-patient");
        UUID patientId = console.createUser(email, PASSWORD);
        insertPatient(patientId, email, "Amel", "Ben Salah");
        UUID otherId = UUID.randomUUID();
        insertPatient(otherId, "other-" + otherId + "@example.com", "Youssef", "Autre");
        String proEmail = email("export-pro");
        UUID proId = console.createUser(proEmail, PASSWORD, "/practitioners");
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?) on conflict (id) do nothing", proId, proEmail, proEmail);
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Leila', 'Ben Ali', ?)",
                proId, specialty);
        appointment(proId, patientId, PAST);
        appointment(proId, otherId, PAST.plus(1, ChronoUnit.HOURS));
        Browser pro = new Browser().login(proEmail, PASSWORD);
        assertThat(pro.postJson("/api/patients/" + patientId + "/reports", "{\"content\": \"Compte-rendu de Amel.\"}")
                .statusCode()).isEqualTo(201);
        assertThat(pro.postJson("/api/patients/" + otherId + "/reports", "{\"content\": \"Secret de l'autre.\"}")
                .statusCode()).isEqualTo(201);
        Browser patient = new Browser();
        assertThat(patient.apiLogin(email, PASSWORD).statusCode()).isEqualTo(200);

        HttpResponse<String> response = patient.call("/api/patients/me/export");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Disposition").orElse("")).contains("medlink-export.json");
        Map<String, Object> body = mapper.readValue(response.body(), Map.class);
        assertThat(body).containsKeys("exportedAt", "profile", "appointments", "reports", "documents");
        Map<String, Object> profile = (Map<String, Object>) body.get("profile");
        assertThat(profile).containsEntry("firstName", "Amel").containsEntry("email", email);
        assertThat((List<?>) body.get("appointments")).hasSize(1);
        List<Map<String, Object>> reports = (List<Map<String, Object>>) body.get("reports");
        assertThat(reports).extracting(report -> report.get("content")).containsExactly("Compte-rendu de Amel.");
        assertThat(response.body()).doesNotContain("Secret de l'autre").doesNotContain("Youssef");
        assertThat(jdbc.queryForList("select outcome from audit_entries where action = 'DATA_READ' "
                + "and resource_type = 'PATIENT_EXPORT' and user_id = ?", String.class, patientId))
                .containsExactly("SUCCESS");
    }

    @Test
    void practitionersStaffAndAnonymousCallersAreRefused() throws Exception {
        String proEmail = email("export-pro2");
        console.createUser(proEmail, PASSWORD, "/practitioners");
        String staffEmail = email("export-staff");
        console.createUser(staffEmail, PASSWORD, "/administrative-staff");

        assertThat(new Browser().login(proEmail, PASSWORD).call("/api/patients/me/export").statusCode()).isEqualTo(403);
        assertThat(new Browser().login(staffEmail, PASSWORD).call("/api/patients/me/export").statusCode()).isEqualTo(403);
        assertThat(new Browser().call("/api/patients/me/export").statusCode()).isEqualTo(401);
    }

    private void insertPatient(UUID id, String email, String firstName, String lastName) {
        jdbc.update("insert into users (id, username, email) values (?, ?, ?) on conflict (id) do nothing", id, email, email);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, ?, ?, ?)",
                id, firstName, lastName, Date.valueOf("1990-04-12"));
    }

    private void appointment(UUID practitioner, UUID patient, Instant start) {
        UUID clinic = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, ?, 'Sousse')", clinic, "Clinique " + clinic);
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", practitioner, clinic);
        UUID slot = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, 'BOOKED')",
                slot, practitioner, clinic, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)));
        jdbc.update("insert into appointments (id, slot_id, patient_id, booked_by, status) values (?, ?, ?, ?, 'BOOKED')",
                UUID.randomUUID(), slot, patient, patient);
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
