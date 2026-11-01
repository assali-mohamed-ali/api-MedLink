package com.example.apimedlink.exception;

import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Error body: {@code {timestamp, status, error, code, message, details}}. React reacts to
 * {@code code} and shows {@code message} (French); {@code details} maps a field to its message.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private ResponseEntity<Map<String, Object>> body(
            HttpStatus status, String code, String message, Map<String, String> details) {
        return ResponseEntity.status(status).body(ErrorBody.of(status, code, message, details));
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

    /** MLink-123/125: a file over the configured multipart limit, before the controller runs. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException ex) {
        return body(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE",
                "Le fichier dépasse la taille maximale de 10 Mo.", null);
    }

    /** A missing title/file part, a missing query parameter or a malformed multipart body. */
    @ExceptionHandler({
        MissingServletRequestPartException.class,
        MissingServletRequestParameterException.class,
        MultipartException.class
    })
    public ResponseEntity<Map<String, Object>> invalidRequest(Exception ex) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "La requête est incomplète.", null);
    }

    /** A path/query value of the wrong type (e.g. size=abc, a document id that is not a UUID). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> typeMismatch(MethodArgumentTypeMismatchException ex) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "La requête est mal formée.", null);
    }

    /** A value rejected by a constraint declared on the controller itself (page, size...). */
    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
    public ResponseEntity<Map<String, Object>> constraintViolation(Exception ex) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains champs sont invalides.", null);
    }

    /** A body whose media type the endpoint does not accept (a document upload is multipart only). */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> unsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Ce type de contenu n'est pas accepté.", null);
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
