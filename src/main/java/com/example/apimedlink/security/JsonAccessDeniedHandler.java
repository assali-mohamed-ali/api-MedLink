package com.example.apimedlink.security;

import com.example.apimedlink.exception.ErrorBody;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every refused access (MLink-58), before a controller (e.g. a write without the CSRF header) or
 * inside one ({@code @PreAuthorize}, {@link OwnershipGuard}): 403 with our JSON error, and one row in
 * the audit trail (MLink-59).
 */
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(JsonAccessDeniedHandler.class);
    private static final int MAX_RESOURCE_LENGTH = 255;

    private final JsonMapper json;
    private final AuditService audit;

    public JsonAccessDeniedHandler(JsonMapper json, AuditService audit) {
        this.json = json;
        this.audit = audit;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException refused)
            throws IOException {
        try {
            // Method and path only: no query string, no body, so no token or value lands in the trail
            String resource = request.getMethod() + " " + request.getRequestURI();
            audit.record(AuditAction.ACCESS_DENIED, "REQUEST",
                    resource.length() > MAX_RESOURCE_LENGTH ? resource.substring(0, MAX_RESOURCE_LENGTH) : resource,
                    AuditOutcome.DENIED);
        } catch (RuntimeException e) {
            // The refusal stands even if it cannot be written down
            log.error("Could not record a refused access in the audit trail", e);
        }
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(ErrorBody.of(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Vous n'avez pas accès à cette ressource.", null)));
    }
}
