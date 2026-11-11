package com.example.apimedlink.controller;

import com.example.apimedlink.dto.LoginRequest;
import com.example.apimedlink.dto.LoginResponse;
import com.example.apimedlink.mapper.LoginMapper;
import com.example.apimedlink.security.LoginSessions;
import com.example.apimedlink.security.MfaPending;
import com.example.apimedlink.security.PasswordChangePending;
import com.example.apimedlink.service.LoginResult;
import com.example.apimedlink.service.LoginService;
import com.example.apimedlink.service.MfaEnrolmentLinks;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint 5 (MLink-55): React posts the email and the password here, never to Keycloak.
 * Contract: docs/sprints/sprint-2/plan.md §4.
 *
 * <p>The audit says LOGIN/SUCCESS only once a session exists. A forced password change is recorded as
 * a challenge (PASSWORD_CHANGE_REQUIRED), and the staff code step records nothing yet.
 */
@RestController
@RequestMapping("/api/auth")
public class LoginController {

    private final LoginService logins;
    private final LoginSessions sessions;
    private final PasswordChangePending passwordChange;
    private final MfaPending mfaPending;
    private final MfaEnrolmentLinks enrolmentLinks;

    public LoginController(LoginService logins, LoginSessions sessions, PasswordChangePending passwordChange,
                           MfaPending mfaPending, MfaEnrolmentLinks enrolmentLinks) {
        this.logins = logins;
        this.sessions = sessions;
        this.passwordChange = passwordChange;
        this.mfaPending = mfaPending;
        this.enrolmentLinks = enrolmentLinks;
    }

    @PostMapping("/login")
    public LoginResponse login(
            @Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        LoginResult result = logins.login(body.email(), body.password());
        if (result instanceof LoginResult.Success success) {
            UUID userId = success.login().user().getId();
            try {
                sessions.open(success.login(), request, response);
            } catch (RuntimeException notOpened) {
                logins.recordLoginFailure(userId);
                throw notOpened;
            }
            logins.recordLoginSuccess(userId);
            return LoginMapper.toResponse(success.login());
        }
        if (result instanceof LoginResult.MfaRequired code) {
            mfaPending.start(code.login(), request);
            if (code.setupRequired()) {
                // The first setup needs the mailed link too: a stolen password alone enrols nothing
                enrolmentLinks.send(code.login().user(), code.login().firstName());
            }
            return LoginResponse.mfaRequired(code.setupRequired());
        }
        LoginResult.PasswordChange change = (LoginResult.PasswordChange) result;
        try {
            passwordChange.start(change.userId(), request);
        } catch (RuntimeException notStarted) {
            logins.recordLoginFailure(change.userId());
            throw notStarted;
        }
        // A challenge, not a login: no session exists until the password is changed
        logins.recordPasswordChangeRequired(change.userId());
        return LoginResponse.forcedPasswordChange();
    }
}
