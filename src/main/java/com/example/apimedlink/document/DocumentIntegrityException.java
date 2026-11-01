package com.example.apimedlink.document;

import com.example.apimedlink.exception.ApiException;
import org.springframework.http.HttpStatus;

/** A stored document no longer decrypts or no longer matches its recorded checksum. */
public class DocumentIntegrityException extends ApiException {

    public DocumentIntegrityException(String message) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", message);
    }

    public DocumentIntegrityException(String message, Throwable cause) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", message);
        initCause(cause);
    }
}
