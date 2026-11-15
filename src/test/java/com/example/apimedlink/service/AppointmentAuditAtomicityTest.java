package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditEntry;
import com.example.apimedlink.repository.AuditEntryRepository;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * MLink-104, code owner's review on #120: a booking and its audit row are one unit. If the audit row cannot be
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

    @Test
    void aBookingAndItsAuditRowAreWrittenTogether() {
        UUID booked = appointments.book(patient(), freeSlot()).getId();

        assertThat(jdbc.queryForList("select action from audit_entries where resource_id = ?", String.class,
                booked.toString())).containsExactly("APPOINTMENT_BOOKED");
    }

    private UUID patient() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", id, "patient-" + id, "patient-" + id + "@example.com");
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Ben Salah', ?)",
                id, Date.valueOf("1990-04-12"));
        return id;
    }

    /** A practitioner of its own, working in Sfax, with one free 30-minute slot there. */
    private UUID freeSlot() {
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
                slot, practitioner, SFAX, Timestamp.from(START), Timestamp.from(START.plus(30, ChronoUnit.MINUTES)));
        return slot;
    }
}
