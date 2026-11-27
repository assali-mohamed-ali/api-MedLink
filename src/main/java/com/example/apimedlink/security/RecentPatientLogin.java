package com.example.apimedlink.security;

import com.example.apimedlink.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** A token refresh does not replace an interactive login for sensitive patient operations. */
@Component
public class RecentPatientLogin {

    private static final Duration MAX_AGE = Duration.ofMinutes(10);

    public void require(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object opened = session == null ? null : session.getAttribute(LoginSessions.OPENED_AT);
        if (!(opened instanceof Instant loggedInAt) || !loggedInAt.plus(MAX_AGE).isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "RECENT_LOGIN_REQUIRED",
                    "Reconnectez-vous avant de poursuivre cette opération.");
        }
    }
}
