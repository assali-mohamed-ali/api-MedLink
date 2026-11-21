package com.example.apimedlink.controller;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.apimedlink.dto.MfaVerifyRequest;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.User;
import com.example.apimedlink.security.LoginSessions;
import com.example.apimedlink.security.MfaPending;
import com.example.apimedlink.service.AuditService;
import com.example.apimedlink.service.KeycloakAdminService;
import com.example.apimedlink.service.KeycloakLoginClient;
import com.example.apimedlink.service.KeycloakTokens;
import com.example.apimedlink.service.Login;
import com.example.apimedlink.service.LoginService;
import com.example.apimedlink.service.MfaEnrolmentLinks;
import com.example.apimedlink.service.MfaService;
import com.example.apimedlink.service.UserProvisioningService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The staff code step when the code is refused (review of #90): a wrong or replayed code and a locked code step
 * are audited as LOGIN/FAILURE for the identified user, before the cleanup, and the code that was typed is never
 * recorded. The failure entry is best effort, like the other login entries. No Spring context.
 */
class MfaCodeFailureAuditTest {

    static final String TYPED_CODE = "123456";

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

    MfaCodeFailureAuditTest() {
        when(tokens.refreshToken()).thenReturn("refresh-token");
        User user = User.builder().id(userId).username("staff@example.com").email("staff@example.com").build();
        login = new Login(user, "Ada", "Lovelace", List.of(), tokens);
        when(pending.current(request)).thenReturn(Optional.of(login));
        when(body.isSixDigits()).thenReturn(true);
        when(body.code()).thenReturn(TYPED_CODE);
    }

    @Test
    void aWrongOrReplayedCodeIsAuditedAsALoginFailureOfTheIdentifiedUser() {
        ApiException wrong = new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CODE", "Code incorrect ou expiré.");
        doThrow(wrong).when(mfa).verify(userId, TYPED_CODE);

        assertThatThrownBy(() -> controller.verify(body, request, response)).isSameAs(wrong);

        verify(logins).recordLoginFailure(userId);
        verify(logins, never()).recordLoginSuccess(any());
        verify(sessions, never()).open(any(), any(), any());
        // The login stays pending so the user can try again: nothing is cleared and no Keycloak session is ended
        verify(pending, never()).clear(any());
        verifyNoMoreInteractions(logins);
    }

    @Test
    void aLockedCodeStepIsAuditedAsALoginFailureBeforeTheLoginIsClosed() {
        ApiException locked = new ApiException(HttpStatus.LOCKED, "MFA_LOCKED", "Trop de codes incorrects.");
        doThrow(locked).when(mfa).verify(userId, TYPED_CODE);

        assertThatThrownBy(() -> controller.verify(body, request, response)).isSameAs(locked);

        InOrder order = inOrder(logins, pending);
        order.verify(logins).recordLoginFailure(userId);
        order.verify(pending).clear(request);
        order.verify(logins).logout("refresh-token");
        verify(logins, never()).recordLoginSuccess(any());
        verify(sessions, never()).open(any(), any(), any());
    }

    @Test
    void theRefusedCodeIsNeverPassedToTheAudit() {
        doThrow(new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CODE", "Code incorrect ou expiré."))
                .when(mfa).verify(userId, TYPED_CODE);
        AuditService audit = mock(AuditService.class);
        LoginService realLogins = new LoginService(mock(KeycloakLoginClient.class),
                mock(KeycloakAdminService.class), mock(UserProvisioningService.class), mfa, audit);
        MfaController withRealLogins = new MfaController(mfa, pending, sessions, realLogins, enrolmentLinks);

        assertThatThrownBy(() -> withRealLogins.verify(body, request, response)).isInstanceOf(ApiException.class);

        // One entry: LOGIN / FAILURE for this user. Its resource is the user id, never the typed code.
        verify(audit).record(AuditAction.LOGIN, "USER", userId.toString(), AuditOutcome.FAILURE, userId);
        verifyNoMoreInteractions(audit);
    }

    @Test
    void aFailureEntryThatCannotBeWrittenNeverHidesTheRefusal() {
        AuditService audit = mock(AuditService.class);
        doThrow(new IllegalStateException("audit down")).when(audit)
                .record(any(), anyString(), anyString(), any(), any());
        LoginService realLogins = new LoginService(mock(KeycloakLoginClient.class),
                mock(KeycloakAdminService.class), mock(UserProvisioningService.class), mfa, audit);
        ApiException wrong = new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CODE", "Code incorrect ou expiré.");
        doThrow(wrong).when(mfa).verify(userId, TYPED_CODE);
        MfaController withRealLogins = new MfaController(mfa, pending, sessions, realLogins, enrolmentLinks);

        assertThatCode(() -> realLogins.recordLoginFailure(userId)).doesNotThrowAnyException();
        // The caller still sees the refusal itself (401 INVALID_CODE), not an audit error
        assertThatThrownBy(() -> withRealLogins.verify(body, request, response)).isSameAs(wrong);
    }
}
