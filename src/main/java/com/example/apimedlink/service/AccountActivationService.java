package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.EmailToken;
import com.example.apimedlink.model.EmailToken.Purpose;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Endpoint 14 (MLink-71): the person a staff account was created for chooses their first password with the
 * mailed link. The token, the password and the verified email are one transaction: when Keycloak refuses the
 * password (policy) or fails, the token stays usable.
 */
@Service
public class AccountActivationService {

    private final EmailTokenService tokens;
    private final KeycloakAdminService keycloak;
    private final TransactionTemplate tx;

    public AccountActivationService(EmailTokenService tokens, KeycloakAdminService keycloak, TransactionTemplate tx) {
        this.tokens = tokens;
        this.keycloak = keycloak;
        this.tx = tx;
    }

    /**
     * @throws ApiException 400 {@code INVALID_TOKEN}, 410 {@code TOKEN_EXPIRED}, 400 {@code PASSWORD_POLICY}
     */
    public void activate(String token, String password) {
        tx.executeWithoutResult(status -> {
            EmailToken used = tokens.consume(token, Purpose.ACTIVATE_ACCOUNT);
            try {
                keycloak.setPassword(used.getUserId(), password);
                keycloak.setEmailVerified(used.getUserId(), true);
            } catch (EntityNotFoundException e) {
                // the account was deleted in Keycloak since the mail was sent
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN",
                        "Ce lien n'est pas valide. Vérifiez qu'il est complet ou demandez-en un nouveau.");
            }
        });
    }
}
