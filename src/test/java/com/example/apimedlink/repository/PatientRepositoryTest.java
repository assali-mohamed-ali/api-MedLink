package com.example.apimedlink.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.model.Patient;
import com.example.apimedlink.model.User;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** A patient IS a user (JOINED inheritance): one id, one row in each table. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class PatientRepositoryTest {

    @Autowired
    UserRepository users;

    @Autowired
    PatientRepository patients;

    @Autowired
    EntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    private static Patient newPatient(UUID id, String email) {
        Patient patient = new Patient("Amel", "Ben Salah", LocalDate.of(1990, 4, 12), "+216 22 123 456",
                "12 rue de Carthage, Sousse");
        patient.setId(id);
        patient.setUsername(email);
        patient.setEmail(email);
        return patient;
    }

    @Test
    void savingAPatientWritesTheUserRowAndThePatientRowUnderOneId() {
        UUID id = UUID.randomUUID();

        patients.saveAndFlush(newPatient(id, "amel-" + id + "@example.com"));
        em.clear();

        assertThat(jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from patients where id = ?", Integer.class, id)).isEqualTo(1);
        User read = users.findById(id).orElseThrow();
        assertThat(read).isInstanceOf(Patient.class);
        assertThat(((Patient) read).getBirthDate()).isEqualTo(LocalDate.of(1990, 4, 12));
        assertThat(read.getCreatedAt()).isNotNull();
    }

    @Test
    void anIdAlreadyHeldByAnAccountCannotBecomeASecondOne() {
        UUID id = UUID.randomUUID();
        users.saveAndFlush(User.builder().id(id).username("taken-" + id).email("taken-" + id + "@example.com").build());
        em.clear();

        assertThatThrownBy(() -> patients.saveAndFlush(newPatient(id, "other-" + id + "@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
