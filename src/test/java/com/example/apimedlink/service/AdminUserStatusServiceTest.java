package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.SessionRevocationStore;
import com.example.apimedlink.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** MLink-70: enabling and disabling an account. Real database; Keycloak is a mock. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AdminUserStatusServiceTest {

    @Autowired
    AdminUserService admin;

    @Autowired
    UserRepository users;

    /** The real store, watched: a test can make its write fail. */
    @MockitoSpyBean
    SessionRevocationStore revocations;

    @MockitoBean
    KeycloakAdminService keycloak;

    @Test
    void disablingAnAccountRevokesFirstThenDisablesAndEndsItsSessions() {
        UUID id = UUID.randomUUID();
        String email = "status-" + id + "@example.com";
        users.save(User.builder().id(id).username(email).email(email).build());

        admin.setEnabled(id, false, UUID.randomUUID());

        InOrder order = inOrder(revocations, keycloak);
        order.verify(revocations).revoke(id);
        order.verify(keycloak).setEnabled(id, false);
        order.verify(keycloak).endSessions(id);
        assertThat(revocations.revokedAt(id)).as("the API's own mark").isPresent();
    }

    @Test
    void aFailedRevocationLeavesKeycloakUntouched() {
        UUID id = UUID.randomUUID();
        doThrow(new DataAccessResourceFailureException("database down")).when(revocations).revoke(id);

        assertThatThrownBy(() -> admin.setEnabled(id, false, UUID.randomUUID()))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessageContaining("database down");

        // Fail closed: nothing was disabled and no session was ended, so staff send the request again
        verify(keycloak).isLastActiveStaff(id); // read-only eligibility check
        verify(keycloak, never()).setEnabled(id, false);
        verify(keycloak, never()).endSessions(id);
    }

    @Test
    void enablingAnAccountOnlyEnablesIt() {
        UUID id = UUID.randomUUID();

        admin.setEnabled(id, true, UUID.randomUUID());

        verify(keycloak).setEnabled(id, true);
        verify(keycloak, never()).endSessions(id);
    }

    @Test
    void staffCannotDisableTheirOwnAccount() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> admin.setEnabled(id, false, id))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("CANNOT_DISABLE_SELF");
                });

        verify(keycloak, never()).setEnabled(id, false);
    }
}
