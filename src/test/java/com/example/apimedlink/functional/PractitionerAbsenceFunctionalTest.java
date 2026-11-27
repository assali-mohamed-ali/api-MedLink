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
 * US-21: a practitioner declares an absence; the free slots it covers are closed; the booked appointments it covers wait
 * for the practitioner's decision, one by one: cancel, or move to the same time next week (a free slot must exist then).
 * The patient is mailed. Another practitioner's appointment is refused, as is one outside an absence. NOT RUN in the
 * session that wrote it (no Docker).
 */
class PractitionerAbsenceFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Practitioner-Absence-2026!";
    static final Instant DAY = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).plus(9, ChronoUnit.HOURS);

    final KeycloakConsole console = new KeycloakConsole();
    final Mailbox mailbox = new Mailbox();
    final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    JdbcTemplate jdbc;

    record Pro(UUID id, UUID clinic, Browser browser) {}

    record Booked(UUID appointment, UUID slot, String patientEmail) {}

    @Test
    @SuppressWarnings("unchecked")
    void declaringAnAbsenceClosesFreeSlotsAndListsTheBookedAppointmentsToDecide() throws Exception {
        Pro pro = practitioner();
        UUID freeInside = slot(pro, DAY.plus(1, ChronoUnit.HOURS), "FREE");
        UUID freeOutside = slot(pro, DAY.plus(3, ChronoUnit.DAYS), "FREE");
        Booked booked = booked(pro, DAY);

        HttpResponse<String> response = pro.browser().postJson("/api/practitioner/absences", absence(DAY.minus(1, ChronoUnit.HOURS),
                DAY.plus(4, ChronoUnit.HOURS)));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        Map<String, Object> body = mapper.readValue(response.body(), Map.class);
        assertThat(body).containsEntry("closedFreeSlots", 1);
        List<Map<String, Object>> toDecide = (List<Map<String, Object>>) body.get("toDecide");
        assertThat(toDecide).extracting(row -> row.get("appointmentId")).containsExactly(booked.appointment().toString());
        assertThat(toDecide.get(0)).containsEntry("patientFirstName", "Amel");
        assertThat(status(freeInside)).isEqualTo("CLOSED");
        assertThat(status(freeOutside)).isEqualTo("FREE");
        assertThat(status(booked.slot())).as("a booked slot waits for the decision").isEqualTo("BOOKED");
        assertThat(pro.browser().call("/api/practitioner/absences").body()).contains((String) body.get("id"));
        String absenceId = (String) body.get("id");
        assertThat(pro.browser().call("/api/practitioner/absences/" + absenceId + "/appointments").body())
                .contains(booked.appointment().toString());
    }

    @Test
    void cancellingAnAppointmentOfAnAbsenceFreesNothingAndMailsThePatient() throws Exception {
        Pro pro = practitioner();
        Booked booked = booked(pro, DAY);
        assertThat(pro.browser().postJson("/api/practitioner/absences", absence(DAY.minus(1, ChronoUnit.HOURS),
                DAY.plus(2, ChronoUnit.HOURS))).statusCode()).isEqualTo(201);

        HttpResponse<String> response = pro.browser().postJson(decision(booked.appointment()), "{\"action\":\"CANCEL\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select status from appointments where id = ?", String.class, booked.appointment()))
                .isEqualTo("CANCELLED");
        assertThat(status(booked.slot())).as("the practitioner is absent: the slot is not bookable").isEqualTo("CLOSED");
        assertThat(mailbox.to(booked.patientEmail(), "Votre rendez-vous MedLink est annulé")).hasSize(1);
        assertThat(pro.browser().postJson(decision(booked.appointment()), "{\"action\":\"CANCEL\"}").statusCode())
                .as("decided once").isEqualTo(409);
    }

    @Test
    void postponingMovesTheAppointmentToTheSameTimeNextWeek() throws Exception {
        Pro pro = practitioner();
        Booked booked = booked(pro, DAY);
        UUID nextWeek = slot(pro, DAY.plus(7, ChronoUnit.DAYS), "FREE");
        assertThat(pro.browser().postJson("/api/practitioner/absences", absence(DAY.minus(1, ChronoUnit.HOURS),
                DAY.plus(2, ChronoUnit.HOURS))).statusCode()).isEqualTo(201);

        HttpResponse<String> response = pro.browser().postJson(decision(booked.appointment()), "{\"action\":\"POSTPONE\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select status from appointments where id = ?", String.class, booked.appointment()))
                .isEqualTo("CANCELLED");
        assertThat(status(nextWeek)).isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject("select count(*) from appointments where slot_id = ? and status = 'BOOKED'",
                Integer.class, nextWeek)).isEqualTo(1);
        assertThat(mailbox.to(booked.patientEmail(), "Votre rendez-vous MedLink est reporté")).hasSize(1);
    }

    @Test
    void withoutAFreeSlotNextWeekNothingChanges() throws Exception {
        Pro pro = practitioner();
        Booked booked = booked(pro, DAY);
        assertThat(pro.browser().postJson("/api/practitioner/absences", absence(DAY.minus(1, ChronoUnit.HOURS),
                DAY.plus(2, ChronoUnit.HOURS))).statusCode()).isEqualTo(201);

        HttpResponse<String> response = pro.browser().postJson(decision(booked.appointment()), "{\"action\":\"POSTPONE\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        assertThat(response.body()).contains("NO_SLOT_NEXT_WEEK");
        assertThat(jdbc.queryForObject("select status from appointments where id = ?", String.class, booked.appointment()))
                .isEqualTo("BOOKED");
        assertThat(mailbox.to(booked.patientEmail(), "Votre rendez-vous MedLink est reporté")).isEmpty();
    }

    @Test
    void anAppointmentOutsideAnAbsenceOrAnotherPractitionersIsRefusedAndABadPeriodIs400() throws Exception {
        Pro pro = practitioner();
        Pro other = practitioner();
        Booked outside = booked(pro, DAY.plus(2, ChronoUnit.DAYS));
        Booked theirs = booked(other, DAY);
        assertThat(pro.browser().postJson("/api/practitioner/absences", absence(DAY.minus(1, ChronoUnit.HOURS),
                DAY.plus(2, ChronoUnit.HOURS))).statusCode()).isEqualTo(201);

        assertThat(pro.browser().postJson(decision(outside.appointment()), "{\"action\":\"CANCEL\"}").statusCode())
                .isEqualTo(409);
        assertThat(pro.browser().postJson(decision(theirs.appointment()), "{\"action\":\"CANCEL\"}").statusCode())
                .isEqualTo(403);
        assertThat(pro.browser().postJson("/api/practitioner/absences", absence(DAY.plus(2, ChronoUnit.HOURS), DAY))
                .statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select status from appointments where id = ?", String.class, theirs.appointment()))
                .isEqualTo("BOOKED");
    }

    @Test
    void patientsStaffAndAnonymousCallersAreRefused() throws Exception {
        String patientEmail = email("absence-patient");
        console.createUser(patientEmail, PASSWORD);
        Browser patient = new Browser();
        assertThat(patient.apiLogin(patientEmail, PASSWORD).statusCode()).isEqualTo(200);
        String staffEmail = email("absence-staff");
        console.createUser(staffEmail, PASSWORD, "/administrative-staff");
        Browser staff = new Browser().login(staffEmail, PASSWORD);
        String body = absence(DAY, DAY.plus(1, ChronoUnit.HOURS));

        for (Browser other : List.of(patient, staff)) {
            assertThat(other.postJson("/api/practitioner/absences", body).statusCode()).isEqualTo(403);
            assertThat(other.call("/api/practitioner/absences").statusCode()).isEqualTo(403);
        }
        assertThat(new Browser().call("/api/practitioner/absences").statusCode()).isEqualTo(401);
    }

    private static String absence(Instant from, Instant to) {
        return "{\"startsAt\":\"" + from + "\",\"endsAt\":\"" + to + "\",\"reason\":\"Congrès\"}";
    }

    private static String decision(UUID appointment) {
        return "/api/practitioner/appointments/" + appointment + "/absence-decision";
    }

    private String status(UUID slot) {
        return jdbc.queryForObject("select status from slots where id = ?", String.class, slot);
    }

    private Pro practitioner() throws Exception {
        String email = email("absence-pro");
        UUID id = console.createUser(email, PASSWORD, "/practitioners");
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?) on conflict (id) do nothing", id, email, email);
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Leila', 'Ben Ali', ?)",
                id, specialty);
        UUID clinic = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, ?, 'Sousse')", clinic, "Clinique " + clinic);
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", id, clinic);
        return new Pro(id, clinic, new Browser().login(email, PASSWORD));
    }

    private UUID slot(Pro pro, Instant start, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, ?)",
                id, pro.id(), pro.clinic(), Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)), status);
        return id;
    }

    /** A patient with an e-mail and a BOOKED appointment on a new slot of the practitioner. */
    private Booked booked(Pro pro, Instant start) {
        UUID patient = UUID.randomUUID();
        String email = "patient-" + patient + "@example.com";
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", patient, email, email);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Ben Salah', ?)",
                patient, Date.valueOf("1990-04-12"));
        UUID slot = slot(pro, start, "BOOKED");
        UUID appointment = UUID.randomUUID();
        jdbc.update("insert into appointments (id, slot_id, patient_id, booked_by, status) values (?, ?, ?, ?, 'BOOKED')",
                appointment, slot, patient, patient);
        return new Booked(appointment, slot, email);
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
