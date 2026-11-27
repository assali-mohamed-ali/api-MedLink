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
 * MLink-35: staff supervise the clinics' activity, read only. They list the practitioners and read any one's agenda:
 * the slots of a period with their clinic and status and, on a booked slot, the patient's names. No medical content
 * (no record, no report) and no change: staff open, close or book nothing here. Patients and practitioners are refused.
 */
class ClinicActivityFunctionalTest extends FunctionalTestBase {

    static final UUID SOUSSE = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    static final UUID TUNIS = UUID.fromString("c1000000-0000-4000-8000-000000000003");
    static final Instant DAY = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(12, ChronoUnit.DAYS)
            .plus(8, ChronoUnit.HOURS);

    final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void staffCanReadCountsForOneClinicAndPeriod() throws Exception {
        UUID clinic = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, ?, ?)", clinic, "Clinique activité", "Tunis");
        UUID practitioner = insertPractitioner("Ines", "Indicateurs", insertSpecialty("Indicateurs"), clinic, TUNIS);
        UUID patient = insertPatient("Ines", "Activité");
        Instant end = DAY.plus(4, ChronoUnit.HOURS);
        insertAppointment(insertSlot(practitioner, clinic, DAY, "BOOKED"), patient);
        insertAppointment(insertSlot(practitioner, clinic, end, "BOOKED"), patient);
        insertAppointment(insertSlot(practitioner, TUNIS, DAY.plus(2, ChronoUnit.HOURS), "BOOKED"), patient);

        UUID free = insertSlot(practitioner, clinic, DAY.plus(1, ChronoUnit.HOURS), "FREE");
        insertSlot(practitioner, clinic, DAY.plus(3, ChronoUnit.HOURS), "CLOSED");
        jdbc.update("insert into appointments (id, slot_id, patient_id, booked_by, status) values (?, ?, ?, ?, 'CANCELLED')",
                UUID.randomUUID(), free, patient, patient);

        Browser staff = staff();
        assertThat(list(staff.call("/api/admin/clinics")))
                .anySatisfy(item -> assertThat(item).containsEntry("id", clinic.toString()));
        HttpResponse<String> response = staff.call(activity(clinic, DAY, end));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(Browser.json(response)).isEqualTo(Map.of("BOOKED", 1, "CANCELLED", 1, "OPEN_SLOTS", 1,
                "BOOKED_SLOTS", 1, "CLOSED_SLOTS", 1, "OCCUPANCY_PERCENT", 50));
        assertThat(staff.call(activity(UUID.randomUUID(), DAY, end)).statusCode()).isEqualTo(404);
        assertThat(staff.call(activity(clinic, end, DAY)).statusCode()).isEqualTo(400);
        assertThat(staff.call(activity(clinic, DAY, DAY.plus(32, ChronoUnit.DAYS))).statusCode()).isEqualTo(400);
        assertThat(Browser.json(staff.call(activity(clinic, DAY.minus(2, ChronoUnit.DAYS), DAY.minus(1, ChronoUnit.DAYS)))))
                .isEqualTo(Map.of("BOOKED", 0, "CANCELLED", 0, "OPEN_SLOTS", 0,
                        "BOOKED_SLOTS", 0, "CLOSED_SLOTS", 0, "OCCUPANCY_PERCENT", 0));
    }

    private static String activity(UUID clinic, Instant from, Instant to) {
        return "/api/admin/clinics/" + clinic + "/activity?from=" + from + "&to=" + to;
    }

    @Test
    void staffListThePractitionersWithTheirSpecialtyAndClinics() throws Exception {
        UUID specialty = insertSpecialty("Angiologie (activité)");
        UUID practitioner = insertPractitioner("Lina", "Activité", specialty, SOUSSE, TUNIS);

        HttpResponse<String> response = staff().call("/api/admin/practitioners");

        List<Map<String, Object>> items = list(response);
        Map<String, Object> lina = items.stream().filter(item -> practitioner.toString().equals(item.get("id")))
                .findFirst().orElseThrow();
        assertThat(lina).containsOnlyKeys("id", "firstName", "lastName", "specialty", "clinics")
                .containsEntry("firstName", "Lina").containsEntry("lastName", "Activité")
                .containsEntry("specialty", Map.of("id", specialty.toString(), "name", "Angiologie (activité)"));
        assertThat(names(lina.get("clinics"))).containsExactly("Clinique MedLink Sousse", "Clinique MedLink Tunis");
        // Names, specialty and clinics only: no email, no username
        assertThat(response.body()).doesNotContain("@");
    }

    @Test
    void staffReadAPractitionersAgendaWithThePatientsNamesAndNothingMedical() throws Exception {
        UUID practitioner = insertPractitioner("Karim", "Planning", insertSpecialty("Allergologie (activité)"), SOUSSE);
        UUID free = insertSlot(practitioner, SOUSSE, DAY, "FREE");
        UUID held = insertSlot(practitioner, SOUSSE, DAY.plus(1, ChronoUnit.HOURS), "BOOKED");
        UUID patient = insertPatient("Amel", "Ben Salah");
        insertAppointment(held, patient);
        insertSlot(practitioner, SOUSSE, DAY.plus(20, ChronoUnit.DAYS), "FREE"); // outside the period

        HttpResponse<String> response = staff().call(agenda(practitioner, DAY.minus(1, ChronoUnit.HOURS),
                DAY.plus(1, ChronoUnit.DAYS)));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.readValue(response.body(), Map.class);
        assertThat(names(body.get("clinics"))).containsExactly("Clinique MedLink Sousse");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        assertThat(items).extracting(item -> item.get("slotId")).containsExactly(free.toString(), held.toString());
        assertThat(items.get(0)).containsEntry("status", "OPEN").doesNotContainKey("appointment");
        assertThat(items.get(1)).containsEntry("status", "BOOKED");
        @SuppressWarnings("unchecked")
        Map<String, Object> appointment = (Map<String, Object>) items.get(1).get("appointment");
        assertThat(appointment.get("patient"))
                .isEqualTo(Map.of("id", patient.toString(), "firstName", "Amel", "lastName", "Ben Salah"));
        // Who, when and where: no birth date, phone, address, email, record or report
        assertThat(response.body()).doesNotContain("1990-04-12", "+21620000000", "1 rue du Test", "@", "birth", "phone",
                "address", "report", "record");
    }

    @Test
    void theAgendaNeedsAPractitionerAndAPeriodOfAtMost31Days() throws Exception {
        UUID practitioner = insertPractitioner("Nadia", "Période", insertSpecialty("Andrologie (activité)"), SOUSSE);
        Browser staff = staff();

        assertThat(staff.call("/api/admin/agenda?from=" + DAY + "&to=" + DAY.plus(1, ChronoUnit.DAYS)).statusCode())
                .as("no practitioner").isEqualTo(400);
        assertThat(staff.call("/api/admin/agenda?practitionerId=" + practitioner).statusCode())
                .as("no period").isEqualTo(400);
        assertThat(staff.call(agenda(practitioner, DAY, DAY.plus(32, ChronoUnit.DAYS))).statusCode())
                .as("more than 31 days").isEqualTo(400);
        assertThat(staff.call(agenda(practitioner, DAY, DAY.minus(1, ChronoUnit.HOURS))).statusCode())
                .as("end before start").isEqualTo(400);
    }

    @Test
    void anUnknownPractitionerIsNotFound() throws Exception {
        HttpResponse<String> response = staff().call(agenda(UUID.randomUUID(), DAY, DAY.plus(1, ChronoUnit.DAYS)));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
        assertThat(response.body()).contains("PRACTITIONER_NOT_FOUND");
    }

    @Test
    void aPatientAndAPractitionerAreRefusedAndAnAnonymousVisitorIsAskedToLogIn() throws Exception {
        Browser practitioner = new Browser().login("practitioner.dev@medlink.local", DEV_USERS_PASSWORD);
        Browser patient = new Browser().login("patient.dev@medlink.local", DEV_USERS_PASSWORD);
        String agenda = agenda(UUID.randomUUID(), DAY, DAY.plus(1, ChronoUnit.DAYS));

        for (String path : List.of("/api/admin/practitioners", agenda, "/api/admin/clinics",
                activity(SOUSSE, DAY, DAY.plus(1, ChronoUnit.DAYS)))) {
            assertThat(practitioner.call(path).statusCode()).as("practitioner " + path).isEqualTo(403);
            assertThat(patient.call(path).statusCode()).as("patient " + path).isEqualTo(403);
            assertThat(new Browser().call(path).statusCode()).as("anonymous " + path).isEqualTo(401);
        }
    }

    private Browser staff() throws Exception {
        return new Browser().login("staff.dev@medlink.local", DEV_USERS_PASSWORD);
    }

    private static String agenda(UUID practitioner, Instant from, Instant to) {
        return "/api/admin/agenda?practitionerId=" + practitioner + "&from=" + from + "&to=" + to;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readValue(response.body(), List.class);
    }

    @SuppressWarnings("unchecked")
    private static List<String> names(Object items) {
        return ((List<Map<String, Object>>) items).stream().map(item -> (String) item.get("name")).toList();
    }

    // ---- test data, written straight to the database --------------------------------------------

    /** Specialty names are unique: each test uses its own. */
    private UUID insertSpecialty(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", id, name);
        return id;
    }

    private UUID insertPractitioner(String firstName, String lastName, UUID specialty, UUID... clinics) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)",
                id, "practitioner-" + id, "practitioner-" + id + "@example.com");
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, ?, ?, ?)",
                id, firstName, lastName, specialty);
        for (UUID clinic : clinics) {
            jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", id, clinic);
        }
        return id;
    }

    private UUID insertSlot(UUID practitioner, UUID clinic, Instant start, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, ?)",
                id, practitioner, clinic, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)), status);
        return id;
    }

    private UUID insertPatient(String firstName, String lastName) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", id, "patient-" + id, "patient-" + id + "@example.com");
        jdbc.update("insert into patients (id, first_name, last_name, birth_date, phone, address) values (?, ?, ?, ?, '+21620000000', '1 rue du Test')",
                id, firstName, lastName, Date.valueOf("1990-04-12"));
        return id;
    }

    private void insertAppointment(UUID slot, UUID patient) {
        jdbc.update("insert into appointments (id, slot_id, patient_id, booked_by, status) values (?, ?, ?, ?, 'BOOKED')",
                UUID.randomUUID(), slot, patient, patient);
    }
}
