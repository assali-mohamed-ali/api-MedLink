package com.example.apimedlink.exception;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * An error the client can act on: an HTTP status, a stable machine {@code code} for React and a
 * French {@code message} for the user. {@code details} maps a field to its own message.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, String> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Map<String, String> getDetails() {
        return details;
    }
}
