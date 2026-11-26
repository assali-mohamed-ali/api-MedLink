package com.example.apimedlink.document;

import com.example.apimedlink.exception.ApiException;
import org.springframework.http.HttpStatus;

/** A document request the client can fix: field validation, size limit or unsupported file type. */
public class DocumentValidationException extends ApiException {

    public DocumentValidationException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static DocumentValidationException invalid(String message) {
        return new DocumentValidationException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    public static DocumentValidationException unsupportedType() {
        return new DocumentValidationException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILE_TYPE",
                "Seuls les fichiers PDF, PNG et JPEG sont acceptés.");
    }

    public static DocumentValidationException tooLarge() {
        return new DocumentValidationException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE",
                "Le fichier dépasse la taille maximale de 10 Mo.");
    }
}
