package com.example.apimedlink.security;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.service.ConsultationReportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * MLink-32, review on #123: what a report write must pass before Spring reads its body. An interceptor runs once the
 * route is matched and before the controller's arguments are resolved, so before one byte of JSON is read:
 * <ol>
 *   <li>the attachment (the record's rule): a practitioner who is not attached gets the record's 403 whatever the body
 *       holds, malformed, huge or unsized;</li>
 *   <li>a hard size: a body must say its length (Content-Length; Tomcat then never reads past it) and stay within
 *       {@link #MAX_BODY_BYTES}. Otherwise 411 or 413, and the body is never read.</li>
 * </ol>
 * The service checks the attachment again and the 10 000 characters after: this guard is the gate, not the rule.
 */
@Component
public class ReportWriteGuard implements HandlerInterceptor {

    /** 10 000 characters, each at its longest JSON escape ({@code é}: 6 bytes), plus the object around them. */
    public static final long MAX_BODY_BYTES = 64 * 1024;

    private final ConsultationReportService reports;
    private final CurrentUser currentUser;

    public ReportWriteGuard(ConsultationReportService reports, CurrentUser currentUser) {
        this.reports = reports;
        this.currentUser = currentUser;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        reports.requireAttached(currentUser.id(), patientId(request));
        long length = request.getContentLengthLong();
        if (length < 0 && request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
            throw new ApiException(HttpStatus.LENGTH_REQUIRED, "LENGTH_REQUIRED",
                    "La taille du compte-rendu doit être indiquée (en-tête Content-Length).");
        }
        if (length > MAX_BODY_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                    "Le compte-rendu dépasse la taille autorisée.");
        }
        return true;
    }

    /** The {id} of the matched route; not a UUID is nobody's attached patient: the same 403. */
    private static UUID patientId(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        Object id = variables instanceof Map<?, ?> map ? map.get("id") : null;
        try {
            return UUID.fromString(String.valueOf(id));
        } catch (IllegalArgumentException notAnId) {
            throw new AccessDeniedException("Not a patient id");
        }
    }
}
