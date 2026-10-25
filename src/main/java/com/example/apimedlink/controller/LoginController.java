package com.example.apimedlink.controller;

import com.example.apimedlink.dto.LoginRequest;
import com.example.apimedlink.dto.LoginResponse;
import com.example.apimedlink.mapper.LoginMapper;
import com.example.apimedlink.security.LoginSessions;
import com.example.apimedlink.security.PasswordChangePending;
import com.example.apimedlink.service.LoginResult;
import com.example.apimedlink.service.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint 5 (MLink-55): React posts the email and the password here, never to Keycloak.
 * Contract: docs/sprints/sprint-2/plan.md §4.
 */
@RestController
@RequestMapping("/api/auth")
public class LoginController {

    private final LoginService logins;
    private final LoginSessions sessions;
    private final PasswordChangePending pending;

    public LoginController(LoginService logins, LoginSessions sessions, PasswordChangePending pending) {
        this.logins = logins;
        this.sessions = sessions;
        this.pending = pending;
    }

    @PostMapping("/login")
    public LoginResponse login(
            @Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        LoginResult result = logins.login(body.email(), body.password());
        if (result instanceof LoginResult.Success success) {
            sessions.open(success.login(), request, response);
            return LoginMapper.toResponse(success.login());
        }
        LoginResult.PasswordChange change = (LoginResult.PasswordChange) result;
        pending.start(change.userId(), request);
        return LoginResponse.forcedPasswordChange();
    }
}
