package com.example.apimedlink.security;

import com.example.apimedlink.exception.ErrorBody;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * A refusal before any controller (MLink-58), e.g. a write without the CSRF header: 403 with our JSON
 * error, the same body as GlobalExceptionHandler's.
 */
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final JsonMapper json;

    public JsonAccessDeniedHandler(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException refused)
            throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(ErrorBody.of(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Vous n'avez pas accès à cette ressource.", null)));
    }
}
