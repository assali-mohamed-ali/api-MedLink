package com.example.apimedlink.service;

import com.example.apimedlink.exception.EmailTakenException;
import com.example.apimedlink.model.EmailToken.Purpose;
import com.example.apimedlink.model.Patient;
import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.PatientRepository;
import com.example.apimedlink.repository.UserRepository;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Patient signup (plan.md §3): Keycloak user first, then our rows under its id in one
 * transaction, then the verification mail. No Keycloak account is left without its rows.
 */
@Service
public class RegistrationService {

    static final String PATIENTS_GROUP = "/patients";

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final KeycloakAdminService keycloak;
    private final UserRepository users;
    private final PatientRepository patients;
    private final EmailTokenService tokens;
    private final EmailVerificationService verification;
    private final TransactionTemplate tx;

    public RegistrationService(
            KeycloakAdminService keycloak,
            UserRepository users,
            PatientRepository patients,
            EmailTokenService tokens,
            EmailVerificationService verification,
            TransactionTemplate tx) {
        this.keycloak = keycloak;
        this.users = users;
        this.patients = patients;
        this.tokens = tokens;
        this.verification = verification;
        this.tx = tx;
    }

    /**
     * @param profile a new patient without its user; the user is created here
     * @return the new user's id (Keycloak's)
     */
    public UUID register(String email, String password, Patient profile) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(normalized) || users.existsByUsername(normalized)) {
            throw new EmailTakenException();
        }
        // Outside any transaction: no database connection held while Keycloak answers
        UUID id = keycloak.createUser(normalized, normalized, profile.getFirstName(), profile.getLastName(), PATIENTS_GROUP);
        String token;
        try {
            keycloak.setPassword(id, password);
            token = tx.execute(status -> {
                User user = users.save(User.builder().id(id).username(normalized).email(normalized).build());
                profile.setUser(user);
                patients.save(profile);
                return tokens.issue(id, Purpose.VERIFY_EMAIL);
            });
        } catch (RuntimeException e) {
            deleteKeycloakUser(id);
            throw e;
        }
        verification.sendVerificationMail(normalized, profile.getFirstName(), token);
        return id;
    }

    private void deleteKeycloakUser(UUID id) {
        try {
            keycloak.deleteUser(id);
        } catch (RuntimeException e) {
            log.error("Signup failed and Keycloak user {} could not be deleted ({}): delete it by hand",
                    id, e.getClass().getSimpleName());
        }
    }
}
