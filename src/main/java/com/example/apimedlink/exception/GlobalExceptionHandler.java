package com.example.apimedlink.exception;

import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Error body: {@code {timestamp, status, error, code, message, details}}. React reacts to
 * {@code code} and shows {@code message} (French); {@code details} maps a field to its message.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private ResponseEntity<Map<String, Object>> body(
            HttpStatus status, String code, String message, Map<String, String> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("code", code);
        body.put("message", message);
        body.put("details", details == null ? Map.of() : details);
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException ex) {
        return body(ex.getStatus(), ex.getCode(), ex.getMessage(), ex.getDetails());
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(EntityNotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), null);
    }

    @ExceptionHandler(EntityExistsException.class)
    public ResponseEntity<Map<String, Object>> conflict(EntityExistsException ex) {
        return body(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), null);
    }

    /** One entry per field (the first message when a field breaks several rules). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex) {
        Map<String, String> details = new LinkedHashMap<>();
        for (FieldError f : ex.getBindingResult().getFieldErrors()) {
            details.putIfAbsent(f.getField(), f.getDefaultMessage());
        }
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains champs sont invalides.", details);
    }

    /** Malformed JSON, or a value of the wrong type (e.g. a date that is not yyyy-MM-dd). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException ex) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "La requête est mal formée.", null);
    }

    /** Logged in full here; the client only learns that something went wrong (no SQL, no values). */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> database(DataAccessException ex) {
        log.error("Database error", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Une erreur inattendue est survenue. Réessayez plus tard.", null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), null);
    }
}
