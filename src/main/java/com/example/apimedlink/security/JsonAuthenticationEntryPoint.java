package com.example.apimedlink.security;

import com.example.apimedlink.exception.ErrorBody;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.json.JsonMapper;

/**
 * The "not logged in" answer (MLink-86): 401 with our JSON error, never a redirect. It covers no
 * session, an unknown or ended session id, and a session Tomcat expired after 15 idle minutes.
 * React shows its login page on this code (MLink-85).
 */
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final JsonMapper json;

    public JsonAuthenticationEntryPoint(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException reason)
            throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(ErrorBody.of(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Vous n'êtes pas connecté ou votre session a expiré. Reconnectez-vous.", null)));
    }
}
