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

    private User savedUser(String email) {
        return users.saveAndFlush(User.builder().id(UUID.randomUUID()).username(email).email(email).build());
    }

    private static Patient patientOf(User user) {
        return Patient.builder()
                .user(user)
                .firstName("Amel")
                .lastName("Ben Salah")
                .birthDate(LocalDate.of(1990, 4, 12))
                .phone("+216 22 123 456")
                .address("12 rue de Carthage, Sousse")
                .build();
    }

    @Test
    void thePatientOfAUserHasTheUsersId() {
        User user = savedUser("amel@example.com");

        patients.saveAndFlush(patientOf(user));
        em.clear();

        Patient read = patients.findById(user.getId()).orElseThrow();
        assertThat(read.getId()).isEqualTo(user.getId());
        assertThat(read.getUser().getEmail()).isEqualTo("amel@example.com");
        assertThat(read.getBirthDate()).isEqualTo(LocalDate.of(1990, 4, 12));
        assertThat(read.getCreatedAt()).isNotNull();
    }

    @Test
    void aSecondPatientForTheSameUserIsRefused() {
        User user = savedUser("twice@example.com");
        patients.saveAndFlush(patientOf(user));
        em.clear();

        Patient second = patientOf(users.getReferenceById(user.getId()));

        assertThatThrownBy(() -> patients.saveAndFlush(second)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
