package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditEntry;
import com.example.apimedlink.repository.AuditEntryRepository;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * MLink-104, code owner's review on #120: a booking and its audit row are one unit; so are a cancel (MLink-106,
 * {@code APPOINTMENT_CANCELLED}) and a move (MLink-107, {@code APPOINTMENT_RESCHEDULED}, on the new appointment). If the audit row cannot be
 * written, the booking is not kept either: no appointment, the slot stays free, so the patient's retry can book it and
 * never meets a SLOT_TAKEN for a booking that answered 500. The audit store's failure cannot be caused through the API
 * on demand, so this test calls the service with the audit repository made to fail (as AgendaAuditAtomicityTest); the
 * success path over HTTP is AppointmentAuditFunctionalTest.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AppointmentAuditAtomicityTest {

    static final UUID SFAX = UUID.fromString("c1000000-0000-4000-8000-000000000002");

    /** In 9 days at 13:00 UTC: future and away from the other tests' slots. */
    static final Instant START = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(9, ChronoUnit.DAYS).plus(13, ChronoUnit.HOURS);

    @Autowired
    AppointmentService appointments;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    AuditEntryRepository entries;

    @Test
    void aBookingWhoseAuditCannotBeWrittenLeavesNoAppointmentAndAFreeSlot() {
        UUID patient = patient();
        UUID slot = freeSlot();
        doThrow(new DataAccessResourceFailureException("audit store unavailable"))
                .when(entries).save(argThat((AuditEntry entry) ->
                        entry != null && entry.getAction() == AuditAction.APPOINTMENT_BOOKED));

        assertThatThrownBy(() -> appointments.book(patient, slot))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(jdbc.queryForObject("select count(*) from appointments where slot_id = ?", Integer.class, slot)).isZero();
        assertThat(jdbc.queryForObject("select status from slots where id = ?", String.class, slot)).isEqualTo("FREE");
    }

    @AfterEach
    void noCaller() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aCancelWhoseAuditCannotBeWrittenLeavesTheAppointmentBookedAndTheSlotTaken() {
        UUID patient = patient();
        UUID slot = freeSlot();
        UUID booked = appointments.book(patient, slot).getId();
        auditFailsFor("APPOINTMENT_CANCELLED");
        callerIs(patient);

        assertThatThrownBy(() -> appointments.cancel(booked)).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(appointmentStatus(booked)).isEqualTo("BOOKED");
        assertThat(slotStatus(slot)).isEqualTo("BOOKED");
    }

    @Test
    void aMoveWhoseAuditCannotBeWrittenKeepsTheOldAppointmentAndLeavesTheNewSlotFree() {
        UUID patient = patient();
        UUID oldSlot = freeSlot();
        UUID newSlot = samePractitionerSlot(oldSlot, 1);
        UUID booked = appointments.book(patient, oldSlot).getId();
        auditFailsFor("APPOINTMENT_RESCHEDULED");
        callerIs(patient);

        assertThatThrownBy(() -> appointments.reschedule(booked, newSlot))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(appointmentStatus(booked)).isEqualTo("BOOKED");
        assertThat(slotStatus(oldSlot)).isEqualTo("BOOKED");
        assertThat(slotStatus(newSlot)).isEqualTo("FREE");
        assertThat(jdbc.queryForObject("select count(*) from appointments where slot_id = ?", Integer.class, newSlot)).isZero();
    }

    /**
     * Code owner's review on the MLink-10 PR: a move to the slot the appointment already holds is refused before anything
     * changes. Without it, the slot was freed then booked again by the same move: a new appointment on the same slot,
     * an audit row, and an APPOINTMENT_MOVED alert "from a date to itself".
     */
    @Test
    void aMoveToTheSameSlotIsRefusedAndChangesNothing() {
        UUID patient = patient();
        UUID slot = freeSlot();
        UUID booked = appointments.book(patient, slot).getId();
        callerIs(patient);

        assertThatThrownBy(() -> appointments.reschedule(booked, slot))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(refused.getCode()).isEqualTo("SAME_SLOT");
                });

        assertThat(appointmentStatus(booked)).isEqualTo("BOOKED");
        assertThat(slotStatus(slot)).isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject("select count(*) from appointments where slot_id = ?", Integer.class, slot)).isEqualTo(1);
        assertThat(actions(booked)).containsExactly("APPOINTMENT_BOOKED");
        assertThat(jdbc.queryForObject("select count(*) from alerts where appointment_id = ?", Integer.class, booked)).isZero();
    }

    /** One row per change, on the appointment it concerns: the move's row is on the new appointment. */
    @Test
    void aCancelAndAMoveEachWriteOneAuditRow() {
        UUID patient = patient();
        // One patient, three different hours: a patient holds one appointment at a time (MLink-167)
        UUID original = freeSlot();
        UUID moved = appointments.book(patient, original).getId();
        UUID cancelled = appointments.book(patient, freeSlot(1)).getId();
        callerIs(patient);

        UUID replacement = appointments.reschedule(moved, samePractitionerSlot(original, 2)).getId();
        appointments.cancel(cancelled);
        appointments.cancel(cancelled);

        assertThat(actions(replacement)).containsExactly("APPOINTMENT_RESCHEDULED");
        assertThat(actions(cancelled)).containsExactly("APPOINTMENT_BOOKED", "APPOINTMENT_CANCELLED");
    }

    @Test
    void aBookingAndItsAuditRowAreWrittenTogether() {
        UUID booked = appointments.book(patient(), freeSlot()).getId();

        assertThat(jdbc.queryForList("select action from audit_entries where resource_id = ?", String.class,
                booked.toString())).containsExactly("APPOINTMENT_BOOKED");
    }

    /** Matched by name: the test also compiles (and fails) on code without the cancel and move events. */
    private void auditFailsFor(String action) {
        doThrow(new DataAccessResourceFailureException("audit store unavailable"))
                .when(entries).save(argThat((AuditEntry entry) -> entry != null && entry.getAction().name().equals(action)));
    }

    /** The holder is the caller, as after a login: cancel and move check the ownership. */
    private static void callerIs(UUID patient) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(patient.toString(), null, "ROLE_PATIENT"));
    }

    private java.util.List<String> actions(UUID appointment) {
        return jdbc.queryForList("select action from audit_entries where resource_id = ? order by timestamp, id",
                String.class, appointment.toString());
    }

    private String appointmentStatus(UUID appointment) {
        return jdbc.queryForObject("select status from appointments where id = ?", String.class, appointment);
    }

    private String slotStatus(UUID slot) {
        return jdbc.queryForObject("select status from slots where id = ?", String.class, slot);
    }

    private UUID patient() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", id, "patient-" + id, "patient-" + id + "@example.com");
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Ben Salah', ?)",
                id, Date.valueOf("1990-04-12"));
        return id;
    }

    private UUID samePractitionerSlot(UUID original, int hoursLater) {
        Instant start = START.plus(hoursLater, ChronoUnit.HOURS);
        UUID slot = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) "
                        + "select ?, practitioner_id, clinic_id, ?, ?, 'FREE' from slots where id = ?",
                slot, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)), original);
        return slot;
    }

    /** A practitioner of its own, working in Sfax, with one free 30-minute slot there. */
    private UUID freeSlot() {
        return freeSlot(0);
    }

    /** As {@link #freeSlot()}, {@code hoursLater} hours after {@link #START}. */
    private UUID freeSlot(int hoursLater) {
        Instant start = START.plus(hoursLater, ChronoUnit.HOURS);
        UUID practitioner = UUID.randomUUID();
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)",
                practitioner, "pro-" + practitioner, "pro-" + practitioner + "@example.com");
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Rim', 'Dev', ?)",
                practitioner, specialty);
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", practitioner, SFAX);
        UUID slot = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, 'FREE')",
                slot, practitioner, SFAX, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)));
        return slot;
    }
}
