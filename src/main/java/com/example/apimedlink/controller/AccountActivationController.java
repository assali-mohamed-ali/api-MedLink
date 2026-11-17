package com.example.apimedlink.controller;

import com.example.apimedlink.dto.ActivateAccountRequest;
import com.example.apimedlink.dto.MessageResponse;
import com.example.apimedlink.dto.ResendVerificationRequest;
import com.example.apimedlink.service.AccountActivationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint 14 (MLink-71): the React page {@code /activer-compte} posts the mailed token and the first password. */
@RestController
@RequestMapping("/api/auth")
public class AccountActivationController {

    static final String RESEND_MESSAGE =
            "Si un compte en attente d'activation existe pour cet e-mail, un nouveau lien a été envoyé.";

    private final AccountActivationService activation;

    public AccountActivationController(AccountActivationService activation) {
        this.activation = activation;
    }

    @PostMapping("/account/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activate(@Valid @RequestBody ActivateAccountRequest request) {
        activation.activate(request.token(), request.password());
    }

    /**
     * Endpoint 15 (MLink-71): a new activation link, when the first mail failed or the link expired. Same answer
     * whether or not the account exists, limited per account (one a minute) and per IP.
     */
    @PostMapping("/account/activate/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse resendActivation(@Valid @RequestBody ResendVerificationRequest request) {
        activation.resend(request.email());
        return new MessageResponse(RESEND_MESSAGE);
    }
}
