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
import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.SessionRevocationStore;
import com.example.apimedlink.repository.UserRepository;
import com.example.apimedlink.security.Roles;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.persistence.EntityNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** MLink-69, MLink-71: account management by staff. Real database; Keycloak and the mail sender are mocks. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AdminUserServiceTest {

    @Autowired
    AdminUserService admin;

    @Autowired
    UserRepository users;

    @Autowired
    SessionRevocationStore revocations;

    @MockitoBean
    KeycloakAdminService keycloak;

    @MockitoBean
    JavaMailSender mailSender;

    UUID keycloakId;
    String email;

    @BeforeEach
    void setUp() {
        keycloakId = UUID.randomUUID();
        email = "salma-" + keycloakId + "@example.com";
        when(keycloak.createUser(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(keycloakId);
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage((Session) null));
    }

    @Test
    void aStaffAccountIsCreatedInKeycloakThenInOurDatabaseUnderTheSameId() {
        KeycloakUserPage.Entry created = admin.create("  " + email.toUpperCase() + " ", "Salma", "Trabelsi",
                Roles.PRACTITIONER);

        verify(keycloak).createUser(email, email, "Salma", "Trabelsi", "/practitioners");
        User row = users.findById(keycloakId).orElseThrow();
        assertThat(row.getEmail()).isEqualTo(email);
        assertThat(row.getUsername()).isEqualTo(email);
        assertThat(created.user().id()).isEqualTo(keycloakId);
        assertThat(created.groupPath()).isEqualTo("/practitioners");
    }

    @Test
    void anEmailAlreadyInOurDatabaseIsRefusedWithoutCallingKeycloak() {
        users.save(User.builder().id(UUID.randomUUID()).username(email).email(email).build());

        assertThatThrownBy(() -> admin.create(email, "Salma", "Trabelsi", Roles.PRACTITIONER))
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

        assertThatThrownBy(() -> admin.create(email, "Salma", "Trabelsi", Roles.ADMINISTRATIVE_STAFF))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("EMAIL_TAKEN");
                });

        assertThat(users.findByEmail(email)).isEmpty();
    }

    @Test
    void aDatabaseFailureDeletesTheKeycloakUser() {
        // Keycloak hands out an id we already hold under another email: our insert fails
        users.save(User.builder().id(keycloakId).username("other-" + email).email("other-" + email).build());

        assertThatThrownBy(() -> admin.create(email, "Salma", "Trabelsi", Roles.PRACTITIONER))
                .isInstanceOf(RuntimeException.class);

        verify(keycloak).deleteUser(keycloakId);
        assertThat(users.findByEmail(email)).isEmpty();
    }

    @Test
    void aPatientAccountCannotBeCreatedHere() {
        assertThatThrownBy(() -> admin.create(email, "Salma", "Trabelsi", Roles.PATIENT))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(keycloak, never()).createUser(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void changingARoleMovesTheUserToTheRolesKeycloakGroup() {
        admin.changeRole(keycloakId, Roles.ADMINISTRATIVE_STAFF);

        verify(keycloak).moveToGroup(keycloakId, "/administrative-staff");
    }

    @Test
    void changingTheRoleOfAnUnknownUserIsNotFound() {
        doThrow(new EntityNotFoundException("unknown")).when(keycloak).moveToGroup(keycloakId, "/practitioners");

        assertThatThrownBy(() -> admin.changeRole(keycloakId, Roles.PRACTITIONER))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void aRoleChangeToPatientIsRefused() {
        assertThatThrownBy(() -> admin.changeRole(keycloakId, Roles.PATIENT)).isInstanceOf(ApiException.class);

        verify(keycloak, never()).moveToGroup(keycloakId, "/patients");
    }

    @Test
    void aRoleChangeMovesTheUserThenRevokesAndEndsTheirSessions() {
        users.save(User.builder().id(keycloakId).username(email).email(email).build());

        admin.changeRole(keycloakId, Roles.PRACTITIONER);

        InOrder order = inOrder(keycloak);
        order.verify(keycloak).moveToGroup(keycloakId, "/practitioners");
        order.verify(keycloak).endSessions(keycloakId);
        assertThat(revocations.revokedAt(keycloakId)).as("the API's own mark").isPresent();
    }
}
