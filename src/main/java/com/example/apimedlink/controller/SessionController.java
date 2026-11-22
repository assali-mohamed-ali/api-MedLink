package com.example.apimedlink.controller;

import com.example.apimedlink.dto.LoginResponse;
import com.example.apimedlink.mapper.LoginMapper;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint 6 (MLink-53): who is logged in, for React after a page load. The answer comes from the session
 * the login opened ({@code LoginSessions}), with no call to Keycloak or the database; no session → 401
 * (the security chain). Contract: docs/sprints/sprint-2/plan.md §4.
 */
@RestController
@RequestMapping("/api/auth")
public class SessionController {

    @GetMapping("/me")
    public LoginResponse me(@AuthenticationPrincipal OidcUser principal) {
        return LoginMapper.toResponse(principal);
    }
}
