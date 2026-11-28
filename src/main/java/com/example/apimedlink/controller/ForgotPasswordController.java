package com.example.apimedlink.controller;

import com.example.apimedlink.dto.ForgotPasswordRequest;
import com.example.apimedlink.dto.MessageResponse;
import com.example.apimedlink.dto.ResetPasswordRequest;
import com.example.apimedlink.service.ForgotPasswordService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-03: "forgot my password", public like the rest of the sign-in flow (CSRF still applies to these POSTs). The page
 * {@code /reinitialiser-mot-de-passe} of the mailed link is part of the API contract.
 */
@RestController
@RequestMapping("/api/auth/password")
public class ForgotPasswordController {

    static final String SENT_MESSAGE =
            "Si un compte existe pour cet e-mail, un lien pour choisir un nouveau mot de passe vient d'être envoyé.";

    private final ForgotPasswordService service;

    public ForgotPasswordController(ForgotPasswordService service) {
        this.service = service;
    }

    @Operation(summary = "Mail a reset link; the same answer whether or not the account exists")
    @PostMapping("/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse forgot(@Valid @RequestBody ForgotPasswordRequest request) {
        service.request(request.email());
        return new MessageResponse(SENT_MESSAGE);
    }

    @Operation(summary = "Choose a new password with the mailed link; ends every session of the account")
    @PostMapping("/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@Valid @RequestBody ResetPasswordRequest request) {
        service.reset(request.token(), request.newPassword());
    }
}
