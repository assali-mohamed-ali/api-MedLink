package com.example.apimedlink.controller;

import com.example.apimedlink.dto.MessageResponse;
import com.example.apimedlink.dto.ResendVerificationRequest;
import com.example.apimedlink.dto.VerifyEmailRequest;
import com.example.apimedlink.service.EmailVerificationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auth BFF (Backend For Frontend): React talks only to this API, which talks to Keycloak.
 * Contracts: docs/sprints/sprint-2/plan.md §4.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthBffController {

    static final String RESEND_MESSAGE =
            "Si un compte non vérifié existe pour cet e-mail, un nouveau lien a été envoyé.";

    private final EmailVerificationService verification;

    public AuthBffController(EmailVerificationService verification) {
        this.verification = verification;
    }

    /** Endpoint 3: the React page {@code /verifier-email} posts the token from the mail. */
    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        verification.verify(request.token());
    }

    /** Endpoint 4: same answer whether or not the account exists. */
    @PostMapping("/verify-email/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        verification.resend(request.email());
        return new MessageResponse(RESEND_MESSAGE);
    }
}
