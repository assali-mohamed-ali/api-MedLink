package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * US-30: the administrative staff book a free slot for a patient, {@code POST /api/admin/appointments}. The appointment
 * is the patient's, booked_by is the staff member; a taken slot gives 409, an unknown patient 404; patients and
 * practitioners are refused. NOT RUN in the session that wrote it (no Docker).
 */
class StaffBookingFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Staff-Booking-2026!";
    static final Instant START = Instant.now().plus(12, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

    final KeycloakConsole console = new KeycloakConsole();

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void staffBookAFreeSlotForAPatient() throws Exception {
        String staffEmail = email("booking-staff");
        UUID staffId = console.createUser(staffEmail, PASSWORD, "/administrative-staff");
        Browser staff = new Browser().login(staffEmail, PASSWORD);
        UUID patient = patient();
        UUID slot = freeSlot();

        HttpResponse<String> response = staff.postJson("/api/admin/appointments", body(patient, slot));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select booked_by from appointments where slot_id = ? and status = 'BOOKED'",
                UUID.class, slot)).isEqualTo(staffId);
        assertThat(jdbc.queryForObject("select patient_id from appointments where slot_id = ?", UUID.class, slot))
                .isEqualTo(patient);
        assertThat(jdbc.queryForObject("select status from slots where id = ?", String.class, slot)).isEqualTo("BOOKED");
        assertThat(staff.postJson("/api/admin/appointments", body(patient(), slot)).statusCode())
                .as("the slot is taken").isEqualTo(409);
        assertThat(staff.postJson("/api/admin/appointments", body(UUID.randomUUID(), freeSlot())).statusCode())
                .as("unknown patient").isEqualTo(404);
    }

    @Test
    void staffCannotBookForAnUnverifiedPatient() throws Exception {
        String email = email("booking-staff");
        console.createUser(email, PASSWORD, "/administrative-staff");
        Browser staff = new Browser().login(email, PASSWORD);
        UUID slot = freeSlot();

        assertThat(staff.postJson("/api/admin/appointments", body(patient(false), slot)).statusCode())
                .isEqualTo(409);
        assertThat(jdbc.queryForObject("select status from slots where id = ?", String.class, slot)).isEqualTo("FREE");
    }

    @Test
    void patientsPractitionersAndAnonymousCallersAreRefused() throws Exception {
        String patientEmail = email("booking-patient");
        console.createUser(patientEmail, PASSWORD);
        Browser patient = new Browser();
        assertThat(patient.apiLogin(patientEmail, PASSWORD).statusCode()).isEqualTo(200);
        String proEmail = email("booking-pro");
        console.createUser(proEmail, PASSWORD, "/practitioners");
        Browser pro = new Browser().login(proEmail, PASSWORD);
        String body = body(patient(), freeSlot());

        for (Browser other : List.of(patient, pro)) {
            assertThat(other.postJson("/api/admin/appointments", body).statusCode()).isEqualTo(403);
        }
        assertThat(new Browser().postJson("/api/admin/appointments", body).statusCode()).isIn(401, 403);
    }

    private static String body(UUID patient, UUID slot) {
        return "{\"patientId\":\"" + patient + "\",\"slotId\":\"" + slot + "\"}";
    }

    private UUID patient() {
        return patient(true);
    }

    private UUID patient(boolean verified) {
        String email = email("assisted-booking");
        UUID id = console.createUser(email, PASSWORD, verified);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", id, email, email);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Ben Salah', ?)",
                id, Date.valueOf("1990-04-12"));
        return id;
    }

    private int next;

    private UUID freeSlot() {
        UUID practitioner = UUID.randomUUID();
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", practitioner, "pro-" + practitioner,
                "pro-" + practitioner + "@example.com");
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Sami', 'Dev', ?)",
                practitioner, specialty);
        UUID clinic = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, ?, 'Sousse')", clinic, "Clinique " + clinic);
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", practitioner, clinic);
        UUID slot = UUID.randomUUID();
        Instant start = START.plus(next++, ChronoUnit.HOURS);
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, 'FREE')",
                slot, practitioner, clinic, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)));
        return slot;
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
