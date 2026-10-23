package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.apimedlink.exception.InvalidPasswordException;
import com.example.apimedlink.exception.KeycloakUnavailableException;
import com.example.apimedlink.exception.PasswordChangeRequiredException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * MLink-91, what the functional tests cannot cause on demand: Keycloak failing between two calls of
 * a password change, and a forced reset landing inside a still-fresh session (the tests renew the
 * token on every call, so that session is already gone). The real behaviour is in
 * PasswordChangeFunctionalTest and ForcePasswordResetFunctionalTest.
 */
class PasswordServiceTest {

    static final String EMAIL = "a@medlink.local";
    static final String CURRENT = "Current-Pass-2026!";
    static final String NEXT = "Next-Pass-2026!";

    final KeycloakAdminService admin = mock(KeycloakAdminService.class);
    final KeycloakLoginClient login = mock(KeycloakLoginClient.class);
    final PasswordService passwords = new PasswordService(admin, login);
    final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(admin.getUser(id)).thenReturn(new KeycloakUser(id, EMAIL, "A", "B", true, true));
    }

    @Test
    void theChangeStandsWhenTheSessionsCannotBeEnded() {
        when(login.passwordGrant(EMAIL, CURRENT)).thenReturn(new KeycloakTokens(null, null, "refresh", null));
        doThrow(new KeycloakUnavailableException("end user sessions: HTTP 500")).when(admin).endSessions(id);

        assertThatCode(() -> passwords.change(id, CURRENT, NEXT))
                .as("the new password is set and cannot be undone (history policy)")
                .doesNotThrowAnyException();
        verify(admin).setPassword(id, NEXT);
    }

    @Test
    void aRefusedNewPasswordEndsOnlyTheCheckSession() {
        when(login.passwordGrant(EMAIL, CURRENT)).thenReturn(new KeycloakTokens(null, null, "refresh", null));
        doThrow(new InvalidPasswordException("invalidPasswordMinLengthMessage", null)).when(admin).setPassword(id, NEXT);

        assertThatThrownBy(() -> passwords.change(id, CURRENT, NEXT)).isInstanceOf(InvalidPasswordException.class);
        verify(login).endSession("refresh");
        verify(admin, never()).endSessions(id);
    }

    @Test
    void aResetInsideAFreshSessionAsksToLogInAgain() {
        when(login.passwordGrant(EMAIL, CURRENT)).thenThrow(new PasswordChangeRequiredException());

        assertThatThrownBy(() -> passwords.change(id, CURRENT, NEXT))
                .hasFieldOrPropertyWithValue("status", HttpStatus.UNAUTHORIZED)
                .hasFieldOrPropertyWithValue("code", "SESSION_EXPIRED");
        verify(admin, never()).setPassword(any(), any());
    }

    @Test
    void theForcedChangeIsOneKeycloakWrite() {
        passwords.changeForced(id, NEXT);

        verify(admin).setPassword(id, NEXT);
        verifyNoMoreInteractions(admin);
        verifyNoInteractions(login);
    }
}
