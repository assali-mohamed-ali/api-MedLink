package com.example.apimedlink.exception;

import org.springframework.http.HttpStatus;

public class EmailTakenException extends ApiException {

    public EmailTakenException() {
        super(HttpStatus.CONFLICT, "EMAIL_TAKEN", "Un compte existe déjà avec cet e-mail.");
    }
}
