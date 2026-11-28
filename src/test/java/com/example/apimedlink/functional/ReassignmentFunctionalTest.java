package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** US-29: real sessions for the staff, holder and two practitioners; no transfer on only one approval. */
class ReassignmentFunctionalTest extends FunctionalTestBase {
    private static final String PASSWORD = "Reassignment-Test-2026!";
    private final KeycloakConsole console = new KeycloakConsole();
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired JdbcTemplate jdbc;

    record Actor(UUID id, Browser browser) {}
    record Fixture(Actor staff, Actor patient, Actor source, Actor target, UUID clinic, UUID oldSlot,
            UUID newSlot, UUID appointment) {}

    @Test
    void twoIndependentApprovalsTransferAtomicallyAndAllowHistoricalClinicRemoval() throws Exception {
        Fixture f = fixture();
        UUID kept = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, 'Other clinic', 'Sousse')", kept);
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", f.source().id(), kept);
        String assignment = "/api/admin/practitioners/" + f.source().id() + "/clinics";
        assertThat(f.staff().browser().putJson(assignment, "{\"clinicIds\":[\"" + kept + "\"]}").statusCode()).isEqualTo(409);
        String proposal = propose(f);
        assertThat(f.patient().browser().call("/api/patients/me/reassignments").body()).contains(proposal);
        assertThat(f.target().browser().call("/api/practitioner/reassignments").body()).contains(proposal);
        assertThat(f.source().browser().call("/api/practitioner/reassignments").body()).doesNotContain(proposal);
        assertThat(f.source().browser().postJson(proDecision(proposal), "{\"accepted\":true}").statusCode()).isEqualTo(403);
        assertThat(patientDecision(f.patient(), proposal, true).body()).contains("\"status\":\"PENDING\"");
        assertThat(status("appointments", f.appointment())).isEqualTo("BOOKED");
        assertThat(status("slots", f.newSlot())).isEqualTo("FREE");

        HttpResponse<String> accepted = f.target().browser().postJson(proDecision(proposal), "{\"accepted\":true}");
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        assertThat(accepted.body()).contains("\"status\":\"DONE\"");
        assertThat(status("appointments", f.appointment())).isEqualTo("CANCELLED");
        assertThat(status("slots", f.oldSlot())).isEqualTo("CLOSED");
        assertThat(status("slots", f.newSlot())).isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject("select count(*) from appointments where slot_id = ? and patient_id = ?",
                Integer.class, f.newSlot(), f.patient().id())).isEqualTo(1);
        assertThat(patientDecision(f.patient(), proposal, true).body()).contains("\"status\":\"DONE\"");
        assertThat(jdbc.queryForObject("select count(*) from appointments where slot_id = ?", Integer.class, f.newSlot())).isEqualTo(1);
        assertThat(f.staff().browser().putJson(assignment, "{\"clinicIds\":[\"" + kept + "\"]}").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select active from practitioner_clinics where practitioner_id = ? and clinic_id = ?",
                Boolean.class, f.source().id(), f.clinic())).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from audit_entries where resource_type = 'APPOINTMENT_REASSIGNMENT' and resource_id = ?",
                Integer.class, proposal)).isGreaterThanOrEqualTo(3);
    }

    @Test
    void aRefusalAndAnUnavailableTargetNeverLoseTheOriginalAppointment() throws Exception {
        Fixture f = fixture();
        String first = propose(f);
        assertThat(patientDecision(f.patient(), first, false).body()).contains("\"status\":\"DECLINED\"");
        assertThat(f.target().browser().postJson(proDecision(first), "{\"accepted\":true}").body())
                .contains("\"status\":\"DECLINED\"");
        assertThat(status("appointments", f.appointment())).isEqualTo("BOOKED");
        String second = propose(f);
        assertThat(patientDecision(f.patient(), second, true).statusCode()).isEqualTo(200);
        Actor other = patient();
        assertThat(other.browser().postJson("/api/appointments", "{\"slotId\":\"" + f.newSlot() + "\"}").statusCode()).isEqualTo(201);
        assertThat(f.target().browser().postJson(proDecision(second), "{\"accepted\":true}").body())
                .contains("\"status\":\"FAILED\"");
        assertThat(status("appointments", f.appointment())).isEqualTo("BOOKED");
        assertThat(status("slots", f.oldSlot())).isEqualTo("BOOKED");
    }

    @Test
    void simultaneousApprovalsMoveOnlyOnceAndOtherPatientsCannotDecide() throws Exception {
        Fixture f = fixture();
        String proposal = propose(f);
        Actor stranger = patient();
        assertThat(patientDecision(stranger, proposal, true).statusCode()).isEqualTo(403);
        assertThat(stranger.browser().call("/api/patients/me/reassignments").body()).doesNotContain(proposal);
        assertThat(f.patient().browser().postJson("/api/admin/reassignments", "{}").statusCode()).isEqualTo(403);
        CompletableFuture<HttpResponse<String>> one = CompletableFuture.supplyAsync(() -> uncheckedDecision(f.patient(), proposal));
        HttpResponse<String> two = f.target().browser().postJson(proDecision(proposal), "{\"accepted\":true}");
        assertThat(one.get().statusCode()).isEqualTo(200);
        assertThat(two.statusCode()).isEqualTo(200);
        assertThat(status("appointment_reassignments", UUID.fromString(proposal))).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("select count(*) from appointments where slot_id = ?", Integer.class, f.newSlot())).isEqualTo(1);
    }

    private HttpResponse<String> uncheckedDecision(Actor patient, String id) {
        try {
            return patientDecision(patient, id, true);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private String propose(Fixture f) throws Exception {
        String body = "{\"appointmentId\":\"" + f.appointment() + "\",\"targetSlotId\":\"" + f.newSlot() + "\"}";
        assertThat(f.staff().browser().call("/api/admin/reassignments/candidates?practitionerId=" + f.source().id()
                + "&clinicId=" + f.clinic()).body()).contains(f.appointment().toString());
        assertThat(f.staff().browser().call("/api/admin/reassignments/targets?appointmentId=" + f.appointment()).body())
                .contains(f.newSlot().toString());
        HttpResponse<String> response = f.staff().browser().postJson("/api/admin/reassignments", body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(f.staff().browser().postJson("/api/admin/reassignments", body).statusCode()).isEqualTo(409);
        return json.readTree(response.body()).get("id").asText();
    }

    private static String proDecision(String id) {
        return "/api/practitioner/reassignments/" + id + "/decision";
    }

    private static HttpResponse<String> patientDecision(Actor patient, String id, boolean accepted) throws Exception {
        return patient.browser().postJson("/api/patients/me/reassignments/" + id + "/decision", "{\"accepted\":" + accepted + "}");
    }

    private String status(String table, UUID id) {
        return jdbc.queryForObject("select status from " + table + " where id = ?", String.class, id);
    }

    private Fixture fixture() throws Exception {
        UUID clinic = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, 'Reassignment clinic', 'Tunis')", clinic);
        Actor staff = actor("/administrative-staff");
        Actor source = practitioner(clinic);
        Actor target = practitioner(clinic);
        Actor patient = patient();
        Instant start = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        UUID oldSlot = slot(source.id(), clinic, start);
        UUID targetSlot = slot(target.id(), clinic, start.plus(2, ChronoUnit.HOURS));
        HttpResponse<String> booked = patient.browser().postJson("/api/appointments", "{\"slotId\":\"" + oldSlot + "\"}");
        assertThat(booked.statusCode()).isEqualTo(201);
        UUID appointment = UUID.fromString(json.readTree(booked.body()).get("id").asText());
        return new Fixture(staff, patient, source, target, clinic, oldSlot, targetSlot, appointment);
    }

    private Actor actor(String group) throws Exception {
        String email = uniqueEmail("reassignment");
        UUID id = console.createUser(email, PASSWORD, group);
        return new Actor(id, new Browser().login(email, PASSWORD));
    }

    private Actor patient() throws Exception {
        String email = uniqueEmail("reassignment-patient");
        UUID id = console.createUser(email, PASSWORD);
        Browser browser = new Browser();
        assertThat(browser.apiLogin(email, PASSWORD).statusCode()).isEqualTo(200);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Test', ?)",
                id, Date.valueOf("1990-04-12"));
        return new Actor(id, browser);
    }

    private Actor practitioner(UUID clinic) throws Exception {
        Actor actor = actor("/practitioners");
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Sami', 'Test', ?)",
                actor.id(), specialty);
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", actor.id(), clinic);
        return actor;
    }

    private UUID slot(UUID practitioner, UUID clinic, Instant start) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, 'FREE')",
                id, practitioner, clinic, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)));
        return id;
    }
}
