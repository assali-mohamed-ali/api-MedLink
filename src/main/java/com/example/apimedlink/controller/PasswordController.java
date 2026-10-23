package com.example.apimedlink.controller;

import com.example.apimedlink.dto.ChangePasswordRequest;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.security.CurrentUser;
import com.example.apimedlink.security.PasswordChangePending;
import com.example.apimedlink.service.PasswordService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint 10 (MLink-91): change the caller's password, never through Keycloak's UI. */
@RestController
@RequestMapping("/api/auth")
public class PasswordController {

    private final PasswordService passwords;
    private final PasswordChangePending pending;
    private final CurrentUser currentUser;

    public PasswordController(PasswordService passwords, PasswordChangePending pending, CurrentUser currentUser) {
        this.passwords = passwords;
        this.pending = pending;
        this.currentUser = currentUser;
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void change(@RequestBody ChangePasswordRequest body, HttpServletRequest request) {
        UUID me = currentUser.idOrNull();
        if (me != null) {
            passwords.change(me, body.currentPassword(), body.newPassword());
            return;
        }
        Optional<UUID> forced = pending.userId(request);
        if (forced.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "Connectez-vous pour changer votre mot de passe.");
        }
        passwords.changeForced(forced.get(), body.newPassword());
        pending.clear(request);
    }
}
