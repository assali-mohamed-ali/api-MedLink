package com.example.apimedlink.exception;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The one error shape of the API, {@code {timestamp, status, error, code, message, details}},
 * built here for the exception handler and for the security filters that answer before any
 * controller runs.
 */
public final class ErrorBody {

    private ErrorBody() {}

    public static Map<String, Object> of(HttpStatus status, String code, String message, Map<String, String> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("code", code);
        body.put("message", message);
        body.put("details", details == null ? Map.of() : details);
        return body;
    }
}
