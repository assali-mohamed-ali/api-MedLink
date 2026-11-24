package com.example.apimedlink.service;

import com.example.apimedlink.exception.EmailTakenException;
import com.example.apimedlink.model.EmailToken.Purpose;
import com.example.apimedlink.model.Patient;
import com.example.apimedlink.repository.PatientRepository;
import com.example.apimedlink.repository.UserRepository;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Patient signup (plan.md §3): Keycloak user first, then our rows under its id in one
 * transaction, then the verification mail. No Keycloak account is left without its rows.
 * A taken email is never revealed: the caller gets the same answer, only the owner is told.
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
    private final MailService mail;
    private final MailThrottle throttle;
    private final TransactionTemplate tx;

    public RegistrationService(
            KeycloakAdminService keycloak,
            UserRepository users,
            PatientRepository patients,
            EmailTokenService tokens,
            EmailVerificationService verification,
            MailService mail,
            MailThrottle throttle,
            TransactionTemplate tx) {
        this.keycloak = keycloak;
        this.users = users;
        this.patients = patients;
        this.tokens = tokens;
        this.verification = verification;
        this.mail = mail;
        this.throttle = throttle;
        this.tx = tx;
    }

    /**
     * Creates the patient, or, when the email already has an account, creates nothing and warns
     * that account's owner. Both end the same way for the caller.
     *
     * @param profile a new patient without id or email: both come from the Keycloak account created here
     */
    public void register(String email, String password, Patient profile) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(normalized) || users.existsByUsername(normalized)) {
            warnOwner(normalized);
            return;
        }
        UUID id;
        try {
            // Outside any transaction: no database connection held while Keycloak answers
            id = keycloak.createUser(normalized, normalized, profile.getFirstName(), profile.getLastName(), PATIENTS_GROUP);
        } catch (EmailTakenException knownToKeycloakOnly) {
            warnOwner(normalized);
            return;
        }
        String token;
        try {
            keycloak.setPassword(id, password);
            token = tx.execute(status -> {
                profile.setId(id);
                profile.setUsername(normalized);
                profile.setEmail(normalized);
                patients.save(profile); // the users row and the patients row, under the Keycloak id
                return tokens.issue(id, Purpose.VERIFY_EMAIL);
            });
        } catch (RuntimeException e) {
            deleteKeycloakUser(id);
            throw e;
        }
        verification.sendVerificationMail(normalized, profile.getFirstName(), token);
    }

    /** One warning per address per interval; a mail failure is only logged, like every mail here. */
    private void warnOwner(String email) {
        if (!throttle.allow("signup-attempt", email)) {
            return;
        }
        try {
            mail.sendSignupAttemptOnExistingAccount(email);
        } catch (MailException e) {
            log.warn("Sign-up attempt warning could not be sent ({})", e.getClass().getSimpleName());
        }
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
