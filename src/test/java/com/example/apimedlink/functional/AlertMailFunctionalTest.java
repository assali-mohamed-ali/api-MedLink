package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.repository.AppointmentRepository;
import com.example.apimedlink.service.AlertService;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MLink-37 (37.4, H-06): the practitioner also gets an e-mail when an appointment is cancelled, in French, with the
 * clinic, the date and the time, read here in the real Mailpit. The cancel route is US-15's (not merged yet), so the
 * method its cancel code calls is called directly, as for 37.2.
 */
class AlertMailFunctionalTest extends FunctionalTestBase {

    static final String SUBJECT = "Un rendez-vous a été annulé";

    /** Friday 8 January 2027, 09:00 UTC = 10:00 in Tunis; one hour more for each test slot. */
    static final Instant FIRST_SLOT = Instant.parse("2027-01-08T09:00:00Z");
    static final AtomicInteger NEXT_SLOT = new AtomicInteger();

    final Mailbox mailbox = new Mailbox();

    @Autowired
    AlertService alerts;

    @Autowired
    AppointmentRepository appointments;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void thePractitionerGetsAMailInFrenchWithTheClinicTheDateAndTheTime() {
        String email = "alert-mail-" + UUID.randomUUID() + "@example.com";
        UUID pro = practitioner(email);
        Instant start = FIRST_SLOT.plus(NEXT_SLOT.getAndIncrement(), ChronoUnit.HOURS);
        UUID appointment = appointment(pro, start, "CANCELLED");

        alerts.onAppointmentCancelled(appointments.findById(appointment).orElseThrow());

        List<Map<?, ?>> mails = mailbox.to(email, SUBJECT);
        assertThat(mails).hasSize(1);
        String text = mailbox.text(mails.get(0));
        assertThat(text).contains("Bonjour Sami,")
                .contains("Amel Ben Salah")
                .contains("Clinique MedLink Mail")
                .contains("vendredi 8 janvier")
                .contains(tunisTime(start))
                .contains("/praticien/agenda");
        // What the practitioner needs only: no birth date, no phone of the patient
        assertThat(text).doesNotContain("1990").doesNotContain("+216");
    }

    @Test
    void noAlertMeansNoMailEitherNotCancelledOrAlreadyAlerted() {
        String email = "alert-mail-" + UUID.randomUUID() + "@example.com";
        UUID pro = practitioner(email);
        UUID booked = appointment(pro, FIRST_SLOT.plus(NEXT_SLOT.getAndIncrement(), ChronoUnit.HOURS), "BOOKED");
        UUID cancelled = appointment(pro, FIRST_SLOT.plus(NEXT_SLOT.getAndIncrement(), ChronoUnit.HOURS), "CANCELLED");

        alerts.onAppointmentCancelled(appointments.findById(booked).orElseThrow());
        alerts.onAppointmentCancelled(appointments.findById(cancelled).orElseThrow());
        alerts.onAppointmentCancelled(appointments.findById(cancelled).orElseThrow());

        assertThat(mailbox.to(email, SUBJECT)).hasSize(1);
    }

    private static String tunisTime(Instant start) {
        return java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.of("Africa/Tunis")).format(start);
    }

    private UUID practitioner(String email) {
        UUID id = UUID.randomUUID();
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", id, email, email);
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Sami', 'Dev', ?)", id, specialty);
        return id;
    }

    private UUID appointment(UUID practitioner, Instant start, String status) {
        UUID clinic = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, 'Clinique MedLink Mail', 'Sousse')", clinic);
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", practitioner, clinic);
        UUID patient = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", patient, "patient-" + patient, "patient-" + patient + "@example.com");
        jdbc.update("insert into patients (id, first_name, last_name, birth_date, phone) values (?, 'Amel', 'Ben Salah', ?, '+21620000000')",
                patient, Date.valueOf("1990-04-12"));
        UUID slot = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, ?)",
                slot, practitioner, clinic, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)),
                "BOOKED".equals(status) ? "BOOKED" : "FREE");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into appointments (id, slot_id, patient_id, booked_by, status) values (?, ?, ?, ?, ?)",
                id, slot, patient, patient, status);
        return id;
    }
}
