package com.example.apimedlink.document;

import com.example.apimedlink.exception.ApiException;
import org.springframework.http.HttpStatus;

/** The object store could not be reached or refused the operation; never exposes its response. */
public class StorageException extends ApiException {

    public StorageException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", message);
    }

    public StorageException(String message, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", message);
        initCause(cause);
    }
}
