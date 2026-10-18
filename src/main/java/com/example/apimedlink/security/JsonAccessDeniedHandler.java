package com.example.apimedlink.security;

import com.example.apimedlink.exception.ErrorBody;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
    /** An id in a path: kept in the trail, since it says which record was targeted. */
    private static final Pattern UUID_SEGMENT =
            Pattern.compile("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}");

    private final JsonMapper json;
    private final AuditService audit;
    private final RouteWords routeWords;

    public JsonAccessDeniedHandler(JsonMapper json, AuditService audit, RouteWords routeWords) {
        this.json = json;
        this.audit = audit;
        this.routeWords = routeWords;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException refused)
            throws IOException {
        try {
            // Method and redacted path only: no query string, no body, so no token or value lands in the trail
            audit.record(AuditAction.ACCESS_DENIED, "REQUEST",
                    resource(request.getMethod(), request.getRequestURI()), AuditOutcome.DENIED);
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

    /**
     * "METHOD /path" for the trail (review on PR #56): a segment is kept only when it is a literal word of
     * one of the API's routes or a UUID; anything else (an email, a name, a word no route has) becomes
     * {redacted}; the whole is cut at 255 characters. An allowlist, so an unforeseen kind of data is
     * redacted by default.
     */
    String resource(String method, String path) {
        String redacted = Arrays.stream(path.split("/", -1))
                .map(segment -> segment.isEmpty() || routeWords.contains(segment) || UUID_SEGMENT.matcher(segment).matches()
                        ? segment : "{redacted}")
                .collect(Collectors.joining("/"));
        String resource = method + " " + redacted;
        return resource.length() > MAX_RESOURCE_LENGTH ? resource.substring(0, MAX_RESOURCE_LENGTH) : resource;
    }
}
