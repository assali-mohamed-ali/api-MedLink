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

/**
 * MLink-31 (31.6): a practitioner opens the record of a patient attached to them, through the real API.
 * "Attached" = the practitioner has a BOOKED appointment with the patient, past or upcoming (a cancelled one
 * does not count). Anyone else gets the same 403, whether the patient exists or not; every read is audited.
 * Each test creates its own practitioners, clinics, patients and appointments.
 */
class PatientRecordFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Patient-Record-2026!";

    /** In three days, 09:00 UTC: the upcoming appointments of the tests. */
    static final Instant DAY = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(3, ChronoUnit.DAYS)
            .plus(9, ChronoUnit.HOURS);

    final KeycloakConsole console = new KeycloakConsole();

    @Autowired
    JdbcTemplate jdbc;

    record Pro(UUID id, Browser browser) {}

    @Test
    void anAttachedPractitionerReadsTheRecordWithTheIdentityTheConsultationNeedsOnly() throws Exception {
        Pro pro = practitioner();
        UUID patient = insertPatient("Amel", "Ben Salah");
        appointment(pro.id(), patient, DAY, "BOOKED");

        HttpResponse<String> response = pro.browser().call(record(patient));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        Map<?, ?> body = Browser.json(response);
        assertThat(body.get("patient")).isEqualTo(Map.of("id", patient.toString(), "firstName", "Amel",
                "lastName", "Ben Salah", "birthDate", "1990-04-12", "phone", "+21620000000"));
        assertThat(body.get("reports")).isEqualTo(List.of()); // MLink-32 fills it
        // Nothing the consultation does not need: no address, no email
        assertThat(response.body()).doesNotContain("rue du Test").doesNotContain("@");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }

    @Test
    void aPastBookedAppointmentStillAttachesThePatient() throws Exception {
        Pro pro = practitioner();
        UUID patient = insertPatient("Youssef", "Trabelsi");
        appointment(pro.id(), patient, Instant.now().minus(7, ChronoUnit.DAYS), "BOOKED");

        assertThat(pro.browser().call(record(patient)).statusCode()).isEqualTo(200);
    }

    @Test
    void aPractitionerWhoIsNotAttachedGetsTheSame403AsForAPatientThatDoesNotExist() throws Exception {
        Pro attached = practitioner();
        Pro other = practitioner();
        UUID patient = insertPatient("Amel", "Ben Salah");
        appointment(attached.id(), patient, DAY, "BOOKED");
        UUID cancelledOnly = insertPatient("Amel", "Cancelled");
        appointment(other.id(), cancelledOnly, DAY.plus(1, ChronoUnit.HOURS), "CANCELLED");

        HttpResponse<String> notAttached = other.browser().call(record(patient));
        HttpResponse<String> onlyCancelled = other.browser().call(record(cancelledOnly));
        HttpResponse<String> unknown = other.browser().call(record(UUID.randomUUID()));
        HttpResponse<String> notAnId = other.browser().call("/api/patients/not-a-uuid/record");

        for (HttpResponse<String> refused : List.of(notAttached, onlyCancelled, unknown, notAnId)) {
            assertThat(refused.statusCode()).as(refused.uri() + " " + refused.body()).isEqualTo(403);
            // The same answer whatever the reason: nobody learns which patients exist
            assertThat(Browser.json(refused).get("code")).isEqualTo(Browser.json(notAttached).get("code"));
            assertThat(Browser.json(refused).get("message")).isEqualTo(Browser.json(notAttached).get("message"));
            assertThat(refused.body()).doesNotContain("Amel");
        }
        assertThat(count(other.id(), "ACCESS_DENIED")).isEqualTo(4);
        assertThat(count(other.id(), "RECORD_VIEWED")).isZero();
    }

    @Test
    void patientsStaffAndAnonymousCallersAreRefused() throws Exception {
        Pro pro = practitioner();
        String patientEmail = email("record-patient");
        UUID patientId = console.createUser(patientEmail, PASSWORD);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?) on conflict (id) do nothing",
                patientId, patientEmail, patientEmail);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Self', ?)",
                patientId, Date.valueOf("1990-04-12"));
        appointment(pro.id(), patientId, DAY, "BOOKED");
        Browser patient = new Browser();
        assertThat(patient.apiLogin(patientEmail, PASSWORD).statusCode()).isEqualTo(200);
        String staffEmail = email("record-staff");
        console.createUser(staffEmail, PASSWORD, "/administrative-staff");
        Browser staff = new Browser().login(staffEmail, PASSWORD);

        // Even the patient concerned: this route is the practitioner's view of the record
        assertThat(patient.call(record(patientId)).statusCode()).isEqualTo(403);
        assertThat(patient.call("/api/patients/not-a-uuid/record").statusCode()).isEqualTo(403);
        assertThat(staff.call(record(patientId)).statusCode()).isEqualTo(403);
        assertThat(new Browser().call(record(patientId)).statusCode()).isEqualTo(401);
    }

    @Test
    void everyReadAddsOneRecordViewedEntryWithTheIdsOnly() throws Exception {
        Pro pro = practitioner();
        UUID patient = insertPatient("Amel", "Audit");
        appointment(pro.id(), patient, DAY, "BOOKED");

        assertThat(pro.browser().call(record(patient)).statusCode()).isEqualTo(200);
        assertThat(pro.browser().call(record(patient)).statusCode()).isEqualTo(200);

        List<Map<String, Object>> rows = jdbc.queryForList("select user_id, resource_type, resource_id, outcome "
                + "from audit_entries where action = 'RECORD_VIEWED' and resource_id = ? order by timestamp", patient.toString());
        assertThat(rows).hasSize(2);
        for (Map<String, Object> row : rows) {
            assertThat(row.get("user_id")).isEqualTo(pro.id());
            assertThat(row.get("resource_type")).isEqualTo("PATIENT_RECORD");
            assertThat(row.get("outcome")).isEqualTo("SUCCESS");
        }
    }

    private Pro practitioner() throws Exception {
        String email = email("record-pro");
        UUID id = console.createUser(email, PASSWORD, "/practitioners");
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        // The login finds this row by id: the practitioner row hangs on it
        jdbc.update("insert into users (id, username, email) values (?, ?, ?) on conflict (id) do nothing", id, email, email);
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Sami', 'Dev', ?)", id, specialty);
        return new Pro(id, new Browser().login(email, PASSWORD));
    }

    private UUID insertPatient(String firstName, String lastName) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", id, "patient-" + id, "patient-" + id + "@example.com");
        jdbc.update("insert into patients (id, first_name, last_name, birth_date, phone, address) "
                + "values (?, ?, ?, ?, '+21620000000', '1 rue du Test')", id, firstName, lastName, Date.valueOf("1990-04-12"));
        return id;
    }

    /** One clinic of the practitioner's, one slot, one appointment of the patient on it. */
    private void appointment(UUID practitioner, UUID patient, Instant start, String status) {
        UUID clinic = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, ?, 'Sousse')", clinic, "Clinique " + clinic);
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", practitioner, clinic);
        UUID slot = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, ?)",
                slot, practitioner, clinic, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)),
                "BOOKED".equals(status) ? "BOOKED" : "FREE");
        jdbc.update("insert into appointments (id, slot_id, patient_id, booked_by, status) values (?, ?, ?, ?, ?)",
                UUID.randomUUID(), slot, patient, patient, status);
    }

    private int count(UUID user, String action) {
        return jdbc.queryForObject("select count(*) from audit_entries where user_id = ? and action = ?", Integer.class, user, action);
    }

    private static String record(UUID patient) {
        return "/api/patients/" + patient + "/record";
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
