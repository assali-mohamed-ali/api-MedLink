package com.example.apimedlink.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.apimedlink.dto.MfaVerifyRequest;
import com.example.apimedlink.model.User;
import com.example.apimedlink.security.LoginSessions;
import com.example.apimedlink.security.MfaPending;
import com.example.apimedlink.service.KeycloakTokens;
import com.example.apimedlink.service.Login;
import com.example.apimedlink.service.LoginService;
import com.example.apimedlink.service.MfaEnrolmentLinks;
import com.example.apimedlink.service.MfaService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The staff code step, when the right code is given but the session cannot be opened (review of #90): the
 * attempt is audited as a failure, never a success, and nothing is left behind: the half-made web session
 * is dropped and the Keycloak session of this login is ended. No Spring context.
 */
class MfaControllerSessionFailureTest {

    final MfaService mfa = mock(MfaService.class);
    final MfaPending pending = mock(MfaPending.class);
    final LoginSessions sessions = mock(LoginSessions.class);
    final LoginService logins = mock(LoginService.class);
    final MfaEnrolmentLinks enrolmentLinks = mock(MfaEnrolmentLinks.class);
    final MfaController controller = new MfaController(mfa, pending, sessions, logins, enrolmentLinks);

    final MockHttpServletRequest request = new MockHttpServletRequest();
    final MockHttpServletResponse response = new MockHttpServletResponse();
    final UUID userId = UUID.randomUUID();
    final MfaVerifyRequest body = mock(MfaVerifyRequest.class);
    final KeycloakTokens tokens = mock(KeycloakTokens.class);
    final Login login;

    MfaControllerSessionFailureTest() {
        when(tokens.refreshToken()).thenReturn("refresh-token");
        User user = User.builder().id(userId).username("staff@example.com").email("staff@example.com").build();
        login = new Login(user, "Ada", "Lovelace", List.of(), tokens);
        when(pending.current(request)).thenReturn(Optional.of(login));
        when(body.isSixDigits()).thenReturn(true);
        when(body.code()).thenReturn("123456");
    }

    @Test
    void aSessionOpenedAfterTheRightCodeIsASuccessWithNoCleanup() {
        assertThat(controller.verify(body, request, response)).isNotNull();

        InOrder order = inOrder(sessions, logins);
        order.verify(sessions).open(login, request, response);
        order.verify(logins).recordLoginSuccess(userId);
        verify(logins, never()).recordLoginFailure(any());
        verify(sessions, never()).abandon(any(), any());
        verify(logins, never()).endSessionQuietly(any());
    }

    @Test
    void aSessionThatCannotBeOpenedAfterTheRightCodeIsAFailureNeverASuccess() {
        doThrow(new IllegalStateException("session store down")).when(sessions).open(any(), any(), any());

        assertThatThrownBy(() -> controller.verify(body, request, response))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("session store down");

        verify(logins).recordLoginFailure(userId);
        verify(logins, never()).recordLoginSuccess(any());
    }

    @Test
    void aSessionThatCannotBeOpenedIsCleanedUpHereAndInKeycloak() {
        doThrow(new IllegalStateException("session store down")).when(sessions).open(any(), any(), any());

        assertThatThrownBy(() -> controller.verify(body, request, response))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("session store down");

        InOrder order = inOrder(logins, sessions);
        order.verify(logins).recordLoginFailure(userId);
        order.verify(sessions).abandon(request, response);
        order.verify(logins).endSessionQuietly("refresh-token");
    }
}
