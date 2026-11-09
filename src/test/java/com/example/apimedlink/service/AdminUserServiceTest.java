package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.exception.EmailTakenException;
import com.example.apimedlink.exception.KeycloakUnavailableException;
import com.example.apimedlink.model.Clinic;
import com.example.apimedlink.model.Specialty;
import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.ClinicRepository;
import com.example.apimedlink.repository.PractitionerRepository;
import com.example.apimedlink.repository.SessionRevocationStore;
import com.example.apimedlink.repository.SpecialtyRepository;
import com.example.apimedlink.repository.UserRepository;
import com.example.apimedlink.security.Roles;
import jakarta.persistence.EntityNotFoundException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** MLink-69: account management by staff. Real database; Keycloak is a mock. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AdminUserServiceTest {

    @Autowired
    AdminUserService admin;

    @Autowired
    UserRepository users;

    @Autowired
    PractitionerRepository practitioners;

    @Autowired
    SpecialtyRepository specialties;

    @Autowired
    ClinicRepository clinics;

    @Autowired
    SessionRevocationStore revocations;

    @Autowired
    JdbcTemplate db;

    @MockitoBean
    KeycloakAdminService keycloak;

    UUID keycloakId;
    String email;
    UUID specialtyId;
    UUID clinicId;

    @BeforeEach
    void setUp() {
        keycloakId = UUID.randomUUID();
        email = "salma-" + keycloakId + "@example.com";
        when(keycloak.createUser(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(keycloakId);
        specialtyId = specialties.save(new Specialty("Spec-" + keycloakId)).getId();
        clinicId = clinics.save(new Clinic("Clinic-" + keycloakId, "Sousse")).getId();
    }

    private void accountOf(UUID id, String mail) {
        users.save(User.builder().id(id).username(mail).email(mail).build());
    }

    // ---- create ----------------------------------------------------------------------------------

    @Test
    void aStaffAccountIsCreatedInKeycloakThenInOurDatabaseUnderTheSameId() {
        KeycloakUserPage.Entry created = admin.create("  " + email.toUpperCase() + " ", "Salma", "Trabelsi",
                Roles.ADMINISTRATIVE_STAFF, null, null);

        verify(keycloak).createUser(email, email, "Salma", "Trabelsi", "/administrative-staff");
        User row = users.findById(keycloakId).orElseThrow();
        assertThat(row.getEmail()).isEqualTo(email);
        assertThat(row.getUsername()).isEqualTo(email);
        assertThat(practitioners.existsById(keycloakId)).as("a staff member has no practitioner profile").isFalse();
        assertThat(created.user().id()).isEqualTo(keycloakId);
        assertThat(created.groupPath()).isEqualTo("/administrative-staff");
    }

    @Test
    void aPractitionerIsCreatedWithTheirProfile() {
        KeycloakUserPage.Entry created = admin.create(email, "Salma", "Trabelsi", Roles.PRACTITIONER,
                specialtyId, Set.of(clinicId));

        verify(keycloak).createUser(email, email, "Salma", "Trabelsi", "/practitioners");
        assertThat(created.groupPath()).isEqualTo("/practitioners");
        assertThat(users.findById(keycloakId)).isPresent();
        assertThat(practitioners.existsById(keycloakId)).as("the profile row").isTrue();
        assertThat(db.queryForObject("select specialty_id from practitioners where id = ?", UUID.class, keycloakId))
                .isEqualTo(specialtyId);
        assertThat(db.queryForObject("select count(*) from practitioner_clinics where practitioner_id = ?",
                Integer.class, keycloakId)).isEqualTo(1);
    }

    @Test
    void aPractitionerWithoutSpecialtyOrClinicIsRefusedBeforeKeycloakIsTouched() {
        assertThatThrownBy(() -> admin.create(email, "Salma", "Trabelsi", Roles.PRACTITIONER, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getDetails()).containsKeys("specialtyId", "clinicIds");
                });

        verify(keycloak, never()).createUser(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aPractitionerWithAnUnknownSpecialtyOrClinicIsRefused() {
        assertThatThrownBy(() -> admin.create(email, "Salma", "Trabelsi", Roles.PRACTITIONER,
                UUID.randomUUID(), Set.of(UUID.randomUUID())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getDetails()).containsKeys("specialtyId", "clinicIds");
                });

        verify(keycloak, never()).createUser(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void anEmailAlreadyInOurDatabaseIsRefusedWithoutCallingKeycloak() {
        accountOf(UUID.randomUUID(), email);

        assertThatThrownBy(() -> admin.create(email, "Salma", "Trabelsi", Roles.ADMINISTRATIVE_STAFF, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("EMAIL_TAKEN");
                });

        verify(keycloak, never()).createUser(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void anEmailKnownOnlyToKeycloakIsRefusedWithEmailTaken() {
        when(keycloak.createUser(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new EmailTakenException());

        assertThatThrownBy(() -> admin.create(email, "Salma", "Trabelsi", Roles.ADMINISTRATIVE_STAFF, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("EMAIL_TAKEN");
                });

        assertThat(users.findByEmail(email)).isEmpty();
    }

    @Test
    void aDatabaseFailureDeletesTheKeycloakUserAndLeavesNoProfile() {
        // Keycloak hands out an id we already hold under another email: our insert fails
        accountOf(keycloakId, "other-" + email);

        assertThatThrownBy(() -> admin.create(email, "Salma", "Trabelsi", Roles.PRACTITIONER,
                specialtyId, Set.of(clinicId))).isInstanceOf(RuntimeException.class);

        verify(keycloak).deleteUser(keycloakId);
        assertThat(users.findByEmail(email)).isEmpty();
        assertThat(practitioners.existsById(keycloakId)).isFalse();
    }

    @Test
    void aPatientAccountCannotBeCreatedHere() {
        assertThatThrownBy(() -> admin.create(email, "Salma", "Trabelsi", Roles.PATIENT, null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(keycloak, never()).createUser(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    // ---- change role -----------------------------------------------------------------------------

    @Test
    void changingAStaffMemberToAnotherStaffRoleMovesTheGroupAndEndsTheirSessions() {
        accountOf(keycloakId, email);

        admin.changeRole(keycloakId, Roles.ADMINISTRATIVE_STAFF, null, null);

        InOrder order = inOrder(keycloak);
        order.verify(keycloak).moveToGroup(keycloakId, "/administrative-staff");
        order.verify(keycloak).endSessions(keycloakId);
        assertThat(revocations.revokedAt(keycloakId)).as("the API's own mark").isPresent();
    }

    @Test
    void becomingAPractitionerCreatesTheProfileFromTheKeycloakNames() {
        accountOf(keycloakId, email);
        when(keycloak.getUser(keycloakId))
                .thenReturn(new KeycloakUser(keycloakId, email, "Salma", "Trabelsi", true, true));

        admin.changeRole(keycloakId, Roles.PRACTITIONER, specialtyId, Set.of(clinicId));

        assertThat(practitioners.existsById(keycloakId)).as("the profile row").isTrue();
        assertThat(db.queryForObject("select first_name from practitioners where id = ?", String.class, keycloakId))
                .isEqualTo("Salma");
        verify(keycloak).moveToGroup(keycloakId, "/practitioners");
        assertThat(revocations.revokedAt(keycloakId)).isPresent();
    }

    @Test
    void becomingAPractitionerWithoutSpecialtyOrClinicChangesNothingAndRevokesNothing() {
        accountOf(keycloakId, email);

        assertThatThrownBy(() -> admin.changeRole(keycloakId, Roles.PRACTITIONER, null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(keycloak, never()).moveToGroup(keycloakId, "/practitioners");
        assertThat(revocations.revokedAt(keycloakId)).as("nothing was changed, so nothing was revoked").isEmpty();
    }

    @Test
    void aPractitionerWithoutSlotsLosesTheirProfileWhenBecomingStaff() {
        accountOf(keycloakId, email);
        db.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'S', 'T', ?)",
                keycloakId, specialtyId);

        admin.changeRole(keycloakId, Roles.ADMINISTRATIVE_STAFF, null, null);

        assertThat(practitioners.existsById(keycloakId)).isFalse();
        assertThat(users.findById(keycloakId)).as("the account stays").isPresent();
        verify(keycloak).moveToGroup(keycloakId, "/administrative-staff");
    }

    @Test
    void aPractitionerWhoHoldsSlotsCannotChangeRole() {
        accountOf(keycloakId, email);
        db.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'S', 'T', ?)",
                keycloakId, specialtyId);
        db.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", keycloakId, clinicId);
        db.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at) "
                + "values (?, ?, ?, now() + interval '1 day', now() + interval '1 day 30 minutes')",
                UUID.randomUUID(), keycloakId, clinicId);

        assertThatThrownBy(() -> admin.changeRole(keycloakId, Roles.ADMINISTRATIVE_STAFF, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("PRACTITIONER_HAS_SLOTS");
                });

        assertThat(practitioners.existsById(keycloakId)).as("the profile is kept").isTrue();
        verify(keycloak, never()).moveToGroup(keycloakId, "/administrative-staff");
        assertThat(revocations.revokedAt(keycloakId)).isEmpty();
    }

    @Test
    void changingTheRoleOfAnUnknownUserIsNotFound() {
        doThrow(new EntityNotFoundException("unknown")).when(keycloak)
                .moveToGroup(keycloakId, "/administrative-staff");

        assertThatThrownBy(() -> admin.changeRole(keycloakId, Roles.ADMINISTRATIVE_STAFF, null, null))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void aRoleChangeToPatientIsRefused() {
        assertThatThrownBy(() -> admin.changeRole(keycloakId, Roles.PATIENT, null, null))
                .isInstanceOf(ApiException.class);

        verify(keycloak, never()).moveToGroup(keycloakId, "/patients");
    }

    @Test
    void aFailureMovingTheUserStillRefusesTheirOpenSessions() {
        accountOf(keycloakId, email);
        doThrow(new KeycloakUnavailableException("removing the old group failed"))
                .when(keycloak).moveToGroup(keycloakId, "/administrative-staff");

        assertThatThrownBy(() -> admin.changeRole(keycloakId, Roles.ADMINISTRATIVE_STAFF, null, null))
                .isInstanceOf(KeycloakUnavailableException.class);

        assertThat(revocations.revokedAt(keycloakId)).as("the API refuses the old sessions anyway").isPresent();
        verify(keycloak, never()).endSessions(keycloakId);
    }
}
