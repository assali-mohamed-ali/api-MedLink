package com.example.apimedlink.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.model.Appointment;
import com.example.apimedlink.model.AppointmentStatus;
import com.example.apimedlink.model.Clinic;
import com.example.apimedlink.model.Patient;
import com.example.apimedlink.model.Practitioner;
import com.example.apimedlink.model.Slot;
import com.example.apimedlink.model.SlotStatus;
import com.example.apimedlink.model.Specialty;
import com.example.apimedlink.model.User;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** The appointment domain entities read and write the V13 tables (MLink-97). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class AppointmentDomainRepositoryTest {

    private static final Instant NINE_AM = Instant.parse("2030-01-07T08:00:00Z");

    @Autowired
    UserRepository users;

    @Autowired
    PractitionerRepository practitioners;

    @Autowired
    PatientRepository patients;

    @Autowired
    ClinicRepository clinics;

    @Autowired
    SpecialtyRepository specialties;

    @Autowired
    SlotRepository slots;

    @Autowired
    AppointmentRepository appointments;

    @Autowired
    EntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void savingAPractitionerWritesTheUserRowAndThePractitionerRowUnderOneId() {
        Practitioner saved = practitioners.saveAndFlush(newPractitioner(clinics.save(new Clinic("Clinique", "Sfax"))));
        UUID id = saved.getId();
        em.clear();

        assertThat(jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, id)).isEqualTo(1);
        User read = users.findById(id).orElseThrow();
        assertThat(read).isInstanceOf(Practitioner.class);
        Practitioner practitioner = (Practitioner) read;
        assertThat(practitioner.getLastName()).isEqualTo("Dev");
        assertThat(practitioner.getSpecialty().getName()).startsWith("Cardiologie");
        assertThat(practitioner.getClinics()).extracting(Clinic::getCity).containsExactly("Sfax");
    }

    @Test
    void aSlotAndTheAppointmentBookedOnItAreReadBack() {
        Clinic clinic = clinics.save(new Clinic("Clinique", "Sousse"));
        // Flushed first: Hibernate writes practitioner_clinics after the slot, which must be in one of those clinics
        Practitioner practitioner = practitioners.saveAndFlush(newPractitioner(clinic));
        Patient patient = patients.save(newPatient());
        Slot slot = slots.save(new Slot(practitioner, clinic, NINE_AM, NINE_AM.plus(30, ChronoUnit.MINUTES)));
        slot.setStatus(SlotStatus.BOOKED);
        Appointment saved = appointments.saveAndFlush(new Appointment(slot, patient.getId(), patient.getId()));
        em.clear();

        Appointment read = appointments.findById(saved.getId()).orElseThrow();
        assertThat(read.getStatus()).isEqualTo(AppointmentStatus.BOOKED);
        assertThat(read.getPatientId()).isEqualTo(patient.getId());
        assertThat(read.getBookedBy()).isEqualTo(patient.getId());
        assertThat(read.getCreatedAt()).isNotNull();
        assertThat(read.getReminderSentAt()).isNull();
        assertThat(read.getSlot().getStatus()).isEqualTo(SlotStatus.BOOKED);
        assertThat(read.getSlot().getStartsAt()).isEqualTo(NINE_AM);
        assertThat(read.getSlot().getPractitioner().getId()).isEqualTo(practitioner.getId());
        assertThat(read.getSlot().getClinic().getCity()).isEqualTo("Sousse");
    }

    private Practitioner newPractitioner(Clinic clinic) {
        UUID id = UUID.randomUUID();
        Practitioner practitioner = new Practitioner("Cardiologue", "Dev",
                specialties.save(new Specialty("Cardiologie " + id)));
        practitioner.setId(id);
        practitioner.setUsername("practitioner-" + id);
        practitioner.setEmail("practitioner-" + id + "@example.com");
        practitioner.getClinics().add(clinic);
        return practitioner;
    }

    private static Patient newPatient() {
        UUID id = UUID.randomUUID();
        Patient patient = new Patient("Amel", "Ben Salah", LocalDate.of(1990, 4, 12), null, null);
        patient.setId(id);
        patient.setUsername("amel-" + id + "@example.com");
        patient.setEmail("amel-" + id + "@example.com");
        return patient;
    }
}
