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

import com.example.apimedlink.dto.LoginRequest;
import com.example.apimedlink.model.User;
import com.example.apimedlink.security.LoginSessions;
import com.example.apimedlink.security.PasswordChangePending;
import com.example.apimedlink.service.KeycloakTokens;
import com.example.apimedlink.service.Login;
import com.example.apimedlink.service.LoginResult;
import com.example.apimedlink.service.LoginService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** LOGIN/SUCCESS is audited only once the session really exists (review of #90). No Spring context. */
class LoginControllerAuditOrderTest {

    final LoginService logins = mock(LoginService.class);
    final LoginSessions sessions = mock(LoginSessions.class);
    final PasswordChangePending pending = mock(PasswordChangePending.class);
    final LoginController controller = new LoginController(logins, sessions, pending);

    final MockHttpServletRequest request = new MockHttpServletRequest();
    final MockHttpServletResponse response = new MockHttpServletResponse();
    final UUID userId = UUID.randomUUID();
    final LoginRequest body = mock(LoginRequest.class);

    LoginControllerAuditOrderTest() {
        when(body.email()).thenReturn("user@example.com");
        when(body.password()).thenReturn("a-password");
    }

    private Login aLogin() {
        User user = User.builder().id(userId).username("user@example.com").email("user@example.com").build();
        return new Login(user, "Ada", "Lovelace", List.of(), mock(KeycloakTokens.class));
    }

    @Test
    void successIsRecordedAfterTheSessionIsOpened() {
        Login login = aLogin();
        when(logins.login("user@example.com", "a-password")).thenReturn(new LoginResult.Success(login));

        controller.login(body, request, response);

        InOrder order = inOrder(sessions, logins);
        order.verify(sessions).open(any(), any(), any());
        order.verify(logins).recordLoginSuccess(userId);
        verify(logins, never()).recordLoginFailure(any());
    }

    @Test
    void aSessionThatCannotBeOpenedIsAFailureNeverASuccess() {
        Login login = aLogin();
        when(logins.login("user@example.com", "a-password")).thenReturn(new LoginResult.Success(login));
        doThrow(new IllegalStateException("session store down")).when(sessions).open(any(), any(), any());

        assertThatThrownBy(() -> controller.login(body, request, response))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session store down");

        verify(logins).recordLoginFailure(userId);
        verify(logins, never()).recordLoginSuccess(any());
    }

    @Test
    void aPasswordChangeIsRecordedAfterItStarted() {
        when(logins.login("user@example.com", "a-password")).thenReturn(new LoginResult.PasswordChange(userId));

        assertThat(controller.login(body, request, response)).isNotNull();

        InOrder order = inOrder(pending, logins);
        order.verify(pending).start(userId, request);
        order.verify(logins).recordLoginSuccess(userId);
        verify(logins, never()).recordLoginFailure(any());
    }

    @Test
    void aPasswordChangeThatCannotStartIsAFailureNeverASuccess() {
        when(logins.login("user@example.com", "a-password")).thenReturn(new LoginResult.PasswordChange(userId));
        doThrow(new IllegalStateException("pending store down")).when(pending).start(any(), any());

        assertThatThrownBy(() -> controller.login(body, request, response))
                .isInstanceOf(IllegalStateException.class);

        verify(logins).recordLoginFailure(userId);
        verify(logins, never()).recordLoginSuccess(any());
    }
}
