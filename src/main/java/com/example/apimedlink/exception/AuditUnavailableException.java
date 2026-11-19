package com.example.apimedlink.exception;

import org.springframework.http.HttpStatus;

/**
 * The audit trail could not be written. A document upload or read is refused rather than done without
 * a trace, so the caller gets a 503 it can retry.
 */
public class AuditUnavailableException extends ApiException {

    public AuditUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "AUDIT_UNAVAILABLE", message);
    }

    public AuditUnavailableException(String message, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "AUDIT_UNAVAILABLE", message);
        initCause(cause);
    }
}
