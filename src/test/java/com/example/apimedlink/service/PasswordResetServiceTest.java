package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.example.apimedlink.exception.KeycloakUnavailableException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * MLink-88, a Keycloak failure between the two steps of a forced reset (the functional tests cannot
 * cause it on demand). Retrying is proven in ForcePasswordResetFunctionalTest.
 */
class PasswordResetServiceTest {

    final KeycloakAdminService admin = mock(KeycloakAdminService.class);
    final PasswordResetService resets = new PasswordResetService(admin);
    final UUID id = UUID.randomUUID();

    @Test
    void newLoginsAreBlockedBeforeSessionsEndAndAFailureIsReportedForARetry() {
        doThrow(new KeycloakUnavailableException("end user sessions: HTTP 500")).when(admin).endSessions(id);

        assertThatThrownBy(() -> resets.force(id))
                .as("503: staff send it again, nothing is rolled back")
                .isInstanceOf(KeycloakUnavailableException.class);
        InOrder order = inOrder(admin);
        order.verify(admin).requirePasswordChange(id);
        order.verify(admin).endSessions(id);
    }
}
