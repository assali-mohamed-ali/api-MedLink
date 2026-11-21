package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

/**
 * Ending the Keycloak session of a login whose web session could not be opened is best effort (review of
 * #90): a second failure must not hide the first one. No Spring context.
 */
class LoginServiceEndSessionQuietlyTest {

    final KeycloakLoginClient keycloak = mock(KeycloakLoginClient.class);
    final LoginService service = new LoginService(keycloak, mock(KeycloakAdminService.class),
            mock(UserProvisioningService.class), mock(MfaService.class), mock(AuditService.class));

    @Test
    void theKeycloakSessionOfTheLoginIsEnded() {
        service.endSessionQuietly("refresh-token");

        verify(keycloak).endSession("refresh-token");
    }

    @Test
    void aKeycloakFailureIsSwallowed() {
        doThrow(new IllegalStateException("keycloak down")).when(keycloak).endSession("refresh-token");

        assertThatCode(() -> service.endSessionQuietly("refresh-token")).doesNotThrowAnyException();

        verify(keycloak).endSession("refresh-token");
    }

    @Test
    void noRefreshTokenMeansNothingToEnd() {
        service.endSessionQuietly(null);

        verify(keycloak, never()).endSession(any());
    }
}
