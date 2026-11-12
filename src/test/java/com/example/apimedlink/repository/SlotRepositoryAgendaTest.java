package com.example.apimedlink.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.model.AppointmentStatus;
import com.example.apimedlink.model.Slot;
import com.example.apimedlink.model.SlotStatus;
import jakarta.persistence.EntityManager;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The queries of the practitioner's agenda (MLink-26 / 26.2): the agenda, the overlap lookup, the ownership
 * lookup and the two conditional status updates. Rows are written by SQL, the queries are the repositories'.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class SlotRepositoryAgendaTest {

    private static final Instant NINE_AM = Instant.parse("2030-01-07T08:00:00Z");

    @Autowired
    SlotRepository slots;

    @Autowired
    AppointmentRepository appointments;

    @Autowired
    ClinicRepository clinics;

    @Autowired
    EntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theAgendaHoldsOnlyThePractitionersSlotsOfThePeriodEarliestFirstWithTheirClinicLoaded() {
        UUID mine = insertPractitioner();
        UUID other = insertPractitioner();
        UUID sousse = insertClinic("Sousse test");
        UUID tunis = insertClinic("Tunis test");
        UUID later = insertSlot(mine, tunis, NINE_AM.plus(1, ChronoUnit.HOURS), NINE_AM.plus(90, ChronoUnit.MINUTES), "FREE");
        UUID sooner = insertSlot(mine, sousse, NINE_AM, NINE_AM.plus(30, ChronoUnit.MINUTES), "BOOKED");
        insertSlot(mine, sousse, NINE_AM.plus(1, ChronoUnit.DAYS), NINE_AM.plus(1, ChronoUnit.DAYS).plus(30, ChronoUnit.MINUTES), "FREE");
        insertSlot(other, sousse, NINE_AM.plus(2, ChronoUnit.HOURS), NINE_AM.plus(150, ChronoUnit.MINUTES), "FREE");
        em.clear();

        List<Slot> agenda = slots.findAgenda(mine, NINE_AM, NINE_AM.plus(6, ChronoUnit.HOURS));

        assertThat(agenda).extracting(Slot::getId).containsExactly(sooner, later);
        // The mapper runs after the transaction (open-in-view is off): the clinic must already be loaded
        assertThat(agenda).allSatisfy(slot -> assertThat(
                em.getEntityManagerFactory().getPersistenceUnitUtil().isLoaded(slot, "clinic")).isTrue());
        assertThat(agenda.get(0).getClinic().getName()).isEqualTo("Sousse test");
    }

    @Test
    void theAppointmentsOfTheAgendaComeWithTheirPatientsNamesAndOnlyTheActiveOnes() {
        UUID practitioner = insertPractitioner();
        UUID clinic = insertClinic("Sousse test");
        UUID booked = insertSlot(practitioner, clinic, NINE_AM, NINE_AM.plus(30, ChronoUnit.MINUTES), "BOOKED");
        UUID freed = insertSlot(practitioner, clinic, NINE_AM.plus(1, ChronoUnit.HOURS), NINE_AM.plus(90, ChronoUnit.MINUTES), "FREE");
        UUID patient = insertPatient("Amel", "Ben Salah");
        UUID active = insertAppointment(booked, patient, "BOOKED");
        insertAppointment(freed, patient, "CANCELLED");

        List<BookedAppointment> found = appointments.findWithPatient(List.of(booked, freed), AppointmentStatus.BOOKED);

        assertThat(found).containsExactly(new BookedAppointment(booked, active, patient, "Amel", "Ben Salah"));
    }

    @Test
    void anOverlapIsFoundInAnyClinicButASlotThatJustEndsOrStartsAtTheBoundaryIsNot() {
        UUID practitioner = insertPractitioner();
        UUID sousse = insertClinic("Sousse test");
        UUID taken = insertSlot(practitioner, sousse, NINE_AM, NINE_AM.plus(1, ChronoUnit.HOURS), "CLOSED");
        em.clear();

        // 09:30-10:30 in another clinic overlaps; a closed slot still holds its time
        List<Slot> inTheWay = slots.findOverlapping(practitioner, NINE_AM.plus(30, ChronoUnit.MINUTES), NINE_AM.plus(90, ChronoUnit.MINUTES));
        assertThat(inTheWay).extracting(Slot::getId).containsExactly(taken);
        assertThat(inTheWay.get(0).getClinic().getName()).isEqualTo("Sousse test");
        // The end is exclusive on both sides
        assertThat(slots.findOverlapping(practitioner, NINE_AM.plus(1, ChronoUnit.HOURS), NINE_AM.plus(2, ChronoUnit.HOURS))).isEmpty();
        assertThat(slots.findOverlapping(practitioner, NINE_AM.minus(1, ChronoUnit.HOURS), NINE_AM)).isEmpty();
        // Someone else's slot is never in the way
        assertThat(slots.findOverlapping(insertPractitioner(), NINE_AM, NINE_AM.plus(1, ChronoUnit.HOURS))).isEmpty();
    }

    @Test
    void aSlotOfSomeoneElseLooksLikeASlotThatDoesNotExist() {
        UUID owner = insertPractitioner();
        UUID clinic = insertClinic("Sousse test");
        UUID slot = insertSlot(owner, clinic, NINE_AM, NINE_AM.plus(30, ChronoUnit.MINUTES), "FREE");

        assertThat(slots.findByIdAndPractitioner_Id(slot, owner)).isPresent();
        assertThat(slots.findByIdAndPractitioner_Id(slot, insertPractitioner())).isEmpty();
        assertThat(slots.findByIdAndPractitioner_Id(UUID.randomUUID(), owner)).isEmpty();
    }

    @Test
    void aFreeSlotIsClosedOnceAndOnlyByItsOwner() {
        UUID owner = insertPractitioner();
        UUID clinic = insertClinic("Sousse test");
        UUID slot = insertSlot(owner, clinic, NINE_AM, NINE_AM.plus(30, ChronoUnit.MINUTES), "FREE");

        assertThat(slots.closeIfFree(slot, insertPractitioner())).as("someone else").isZero();
        assertThat(slots.closeIfFree(slot, owner)).isEqualTo(1);
        assertThat(slots.closeIfFree(slot, owner)).as("already closed").isZero();
        assertThat(status(slot)).isEqualTo(SlotStatus.CLOSED.name());
    }

    @Test
    void aSlotWithAnActiveAppointmentIsNeverClosedEvenIfItIsStillMarkedFree() {
        UUID owner = insertPractitioner();
        UUID clinic = insertClinic("Sousse test");
        UUID slot = insertSlot(owner, clinic, NINE_AM, NINE_AM.plus(30, ChronoUnit.MINUTES), "FREE");
        insertAppointment(slot, insertPatient("Amel", "Ben Salah"), "BOOKED");

        assertThat(slots.closeIfFree(slot, owner)).isZero();
        assertThat(status(slot)).isEqualTo("FREE");
    }

    @Test
    void aSlotWhoseOnlyAppointmentWasCancelledCanBeClosed() {
        UUID owner = insertPractitioner();
        UUID clinic = insertClinic("Sousse test");
        UUID slot = insertSlot(owner, clinic, NINE_AM, NINE_AM.plus(30, ChronoUnit.MINUTES), "FREE");
        insertAppointment(slot, insertPatient("Amel", "Ben Salah"), "CANCELLED");

        assertThat(slots.closeIfFree(slot, owner)).isEqualTo(1);
    }

    @Test
    void aClosedSlotIsReopenedOnlyBeforeItStartsAndOnlyByItsOwner() {
        UUID owner = insertPractitioner();
        UUID clinic = insertClinic("Sousse test");
        UUID slot = insertSlot(owner, clinic, NINE_AM, NINE_AM.plus(30, ChronoUnit.MINUTES), "CLOSED");

        assertThat(slots.reopenIfClosed(slot, insertPractitioner(), NINE_AM.minus(1, ChronoUnit.DAYS))).as("someone else").isZero();
        assertThat(slots.reopenIfClosed(slot, owner, NINE_AM.plus(1, ChronoUnit.MINUTES))).as("already started").isZero();
        assertThat(slots.reopenIfClosed(slot, owner, NINE_AM.minus(1, ChronoUnit.DAYS))).isEqualTo(1);
        assertThat(status(slot)).isEqualTo("FREE");
    }

    @Test
    void thePractitionersClinicsAreTheOnesTheyWorkInByName() {
        UUID practitioner = insertPractitioner();
        UUID tunis = insertClinic("B Tunis test");
        UUID sousse = insertClinic("A Sousse test");
        insertClinic("C not mine");
        worksIn(practitioner, tunis);
        worksIn(practitioner, sousse);

        assertThat(clinics.findOfPractitioner(practitioner)).extracting(clinic -> clinic.getName())
                .containsExactly("A Sousse test", "B Tunis test");
        assertThat(clinics.findOfPractitioner(insertPractitioner())).isEmpty();
    }

    // ---- test data, written by SQL ---------------------------------------------------------------

    private String status(UUID slot) {
        em.flush();
        em.clear();
        return jdbc.queryForObject("select status from slots where id = ?", String.class, slot);
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)",
                id, "user-" + id, "user-" + id + "@example.com");
        return id;
    }

    private UUID insertPatient(String firstName, String lastName) {
        UUID id = insertUser();
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, ?, ?, ?)",
                id, firstName, lastName, Date.valueOf("1990-04-12"));
        return id;
    }

    private UUID insertPractitioner() {
        UUID id = insertUser();
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Sami', 'Dev', ?)",
                id, specialty);
        return id;
    }

    private UUID insertClinic(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, ?, 'Sousse')", id, name);
        return id;
    }

    private void worksIn(UUID practitioner, UUID clinic) {
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?) on conflict do nothing",
                practitioner, clinic);
    }

    /** A slot where it may be: the practitioner works in that clinic. */
    private UUID insertSlot(UUID practitioner, UUID clinic, Instant start, Instant end, String status) {
        worksIn(practitioner, clinic);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, ?)",
                id, practitioner, clinic, Timestamp.from(start), Timestamp.from(end), status);
        return id;
    }

    private UUID insertAppointment(UUID slot, UUID patient, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into appointments (id, slot_id, patient_id, booked_by, status) values (?, ?, ?, ?, ?)",
                id, slot, patient, patient, status);
        return id;
    }
}
