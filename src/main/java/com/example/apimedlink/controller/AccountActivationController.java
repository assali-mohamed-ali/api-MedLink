package com.example.apimedlink.controller;

import com.example.apimedlink.dto.ActivateAccountRequest;
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

    private final AccountActivationService activation;

    public AccountActivationController(AccountActivationService activation) {
        this.activation = activation;
    }

    @PostMapping("/account/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activate(@Valid @RequestBody ActivateAccountRequest request) {
        activation.activate(request.token(), request.password());
    }
}
