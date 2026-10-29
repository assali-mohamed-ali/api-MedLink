package com.example.apimedlink.controller;

import com.example.apimedlink.dto.LoginResponse;
import com.example.apimedlink.dto.MfaSetupResponse;
import com.example.apimedlink.dto.MfaVerifyRequest;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.mapper.LoginMapper;
import com.example.apimedlink.mapper.MfaMapper;
import com.example.apimedlink.security.LoginSessions;
import com.example.apimedlink.security.MfaPending;
import com.example.apimedlink.service.Login;
import com.example.apimedlink.service.LoginService;
import com.example.apimedlink.service.MfaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints 8 and 9 (MLink-62/63): the staff code step after a practitioner's or staff member's password.
 * Only the pending code step that login started gets past them; a full session does not.
 * Contract: docs/sprints/sprint-2/plan.md §4.
 */
@RestController
@RequestMapping("/api/auth/mfa")
public class MfaController {

    private final MfaService mfa;
    private final MfaPending pending;
    private final LoginSessions sessions;
    private final LoginService logins;

    public MfaController(MfaService mfa, MfaPending pending, LoginSessions sessions, LoginService logins) {
        this.mfa = mfa;
        this.pending = pending;
        this.sessions = sessions;
        this.logins = logins;
    }

    @PostMapping("/setup")
    public MfaSetupResponse setup(HttpServletRequest request) {
        Login login = pendingLogin(request);
        return MfaMapper.toSetupResponse(mfa.startEnrolment(login.user().getId(), login.user().getEmail()));
    }

    @PostMapping("/verify")
    public LoginResponse verify(@RequestBody MfaVerifyRequest body, HttpServletRequest request,
                                HttpServletResponse response) {
        Login login = pendingLogin(request);
        if (!body.isSixDigits()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains champs sont invalides.",
                    Map.of("code", "Le code comporte 6 chiffres."));
        }
        try {
            mfa.verify(login.user().getId(), body.code());
        } catch (ApiException refused) {
            if (refused.getStatus() == HttpStatus.LOCKED) {
                // Locked: this login is over, here and in Keycloak; the user logs in again later
                pending.clear(request);
                logins.logout(login.tokens().refreshToken());
            }
            throw refused;
        }
        sessions.open(login, request, response);
        return LoginMapper.toResponse(login);
    }

    private Login pendingLogin(HttpServletRequest request) {
        return pending.current(request).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                "Connectez-vous avec votre e-mail et votre mot de passe."));
    }
}
