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
 * MLink-104 (story MLink-27): every booking leaves one line in the shared audit trail (MLink-59): who booked, which
 * appointment, SUCCESS. Ids only, never a name or a medical detail. A refused booking writes no SUCCESS.
 */
class AppointmentAuditFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Booking-Audit-2026!";

    /** Tomorrow 09:00 UTC: the slots of a test start from there. */
    static final Instant DAY = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS)
            .plus(9, ChronoUnit.HOURS);

    final KeycloakConsole console = new KeycloakConsole();
    final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aBookingWritesExactlyOneAuditLineWithTheCallerTheAppointmentAndSuccess() throws Exception {
        UUID slot = insertSlot(insertPractitioner("Rim", "Jaziri", insertSpecialty("Cardiologie (audit)")),
                insertClinic("Clinique Audit Sfax"), DAY, "FREE");
        Patient patient = patient();

        HttpResponse<String> response =
                patient.browser().postJson("/api/appointments", "{\"slotId\":\"" + slot + "\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        String appointment = (String) body(response).get("id");
        List<Map<String, Object>> lines = jdbc.queryForList("""
                select user_id, resource_type, resource_id, outcome, ip_address from audit_entries
                where action = 'APPOINTMENT_BOOKED' and user_id = ?""", patient.id());
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0))
                .containsEntry("user_id", patient.id())
                .containsEntry("resource_type", "APPOINTMENT")
                .containsEntry("resource_id", appointment)
                .containsEntry("outcome", "SUCCESS");
        assertThat(lines.get(0).get("ip_address")).isNotNull();
    }

    @Test
    void aRefusedBookingWritesNoSuccessLine() throws Exception {
        UUID slot = insertSlot(insertPractitioner("Omar", "Gharbi", insertSpecialty("Pédiatrie (audit)")),
                insertClinic("Clinique Audit Sousse"), DAY, "FREE");
        Patient first = patient();
        Patient late = patient();
        assertThat(first.browser().postJson("/api/appointments", "{\"slotId\":\"" + slot + "\"}").statusCode())
                .isEqualTo(201);

        HttpResponse<String> taken = late.browser().postJson("/api/appointments", "{\"slotId\":\"" + slot + "\"}");

        assertThat(taken.statusCode()).as(taken.body()).isEqualTo(409);
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_entries
                where action = 'APPOINTMENT_BOOKED' and outcome = 'SUCCESS' and user_id = ?""", Integer.class, late.id()))
                .isZero();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpResponse<String> response) {
        return json.readValue(response.body(), Map.class);
    }

    /** A logged-in patient as registration leaves them: a users row (the login) and a patients row. */
    record Patient(UUID id, Browser browser) {}

    private Patient patient() throws Exception {
        String email = uniqueEmail("booking-audit");
        UUID id = console.createUser(email, PASSWORD);
        Browser browser = new Browser();
        assertThat(browser.apiLogin(email, PASSWORD).statusCode()).as("patient logged in").isEqualTo(200);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Ben Salah', ?)",
                id, Date.valueOf("1990-04-12"));
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
