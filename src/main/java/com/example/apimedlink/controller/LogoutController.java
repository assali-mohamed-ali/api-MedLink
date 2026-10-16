package com.example.apimedlink.controller;

import com.example.apimedlink.security.LoginSessions;
import com.example.apimedlink.service.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint 7 (MLink-54): React posts here (session + CSRF header) and goes to /connexion.
 * Our session ends first; Keycloak's then, best effort. Contract: docs/sprints/sprint-2/plan.md §4.
 */
@RestController
@RequestMapping("/api/auth")
public class LogoutController {

    private final LoginSessions sessions;
    private final LoginService logins;

    public LogoutController(LoginSessions sessions, LoginService logins) {
        this.sessions = sessions;
        this.logins = logins;
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = sessions.close(request, response);
        logins.logout(refreshToken);
    }
}
