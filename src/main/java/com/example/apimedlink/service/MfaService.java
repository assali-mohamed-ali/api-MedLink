package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.UserMfa;
import com.example.apimedlink.repository.UserMfaStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Endpoints 8 and 9 (plan.md §4), the staff code rules: a practitioner or staff member enrols a TOTP secret
 * (MLink-62), then each login needs a current code (MLink-63). The first good code enables the secret, a code
 * works once, and 5 wrong codes lock the code step for 15 minutes, across logins. Logs name the user and the
 * outcome only, never the secret or a code.
 */
@Service
public class MfaService {

    private static final Logger log = LoggerFactory.getLogger(MfaService.class);
    static final int MAX_WRONG_CODES = 5;
    static final Duration LOCK = Duration.ofMinutes(15);

    /** What the user's authenticator app needs: the Base32 secret and the otpauth URL (the QR code's text). */
    public record Enrolment(String secret, String otpauthUrl) {}

    private final UserMfaStore store;
    private final MfaSecretCipher cipher;
    private final Clock clock;

    public MfaService(UserMfaStore store, MfaSecretCipher cipher, Clock clock) {
        this.store = store;
        this.cipher = cipher;
        this.clock = clock;
    }

    public boolean isEnrolled(UUID userId) {
        return store.isEnabled(userId);
    }

    /** @throws ApiException 409 {@code MFA_ALREADY_ENROLLED}, before anything else is checked for a setup */
    public void requireNotEnrolled(UUID userId) {
        if (isEnrolled(userId)) {
            throw alreadyEnrolled();
        }
    }

    /**
     * A new secret, stored sealed and not yet enabled; it replaces one that was never used.
     *
     * @throws ApiException 409 {@code MFA_ALREADY_ENROLLED}
     */
    public Enrolment startEnrolment(UUID userId, String email) {
        byte[] secret = Totp.newSecret();
        if (!store.saveSecret(userId, cipher.encrypt(userId, secret))) {
            throw alreadyEnrolled();
        }
        log.info("Staff code enrolment started for user {}", userId);
        String base32 = Totp.base32(secret);
        return new Enrolment(base32, Totp.otpauthUrl(email, base32));
    }

    /**
     * Accepts a current code once, and enables the secret the first time.
     *
     * @throws ApiException 401 {@code INVALID_CODE} (wrong, already used, or no secret), 423 {@code MFA_LOCKED}
     */
    public void verify(UUID userId, String code) {
        Instant now = clock.instant();
        Optional<UserMfa> mfa = store.find(userId);
        if (mfa.isEmpty()) {
            throw invalidCode();
        }
        OptionalLong step = Totp.matchingStep(secretOf(userId, mfa.get()), code, now);
        // The store decides atomically: markUsed refuses a used step and any code while locked, and
        // registerFailure counts nothing more during a lock but returns it
        if (step.isPresent() && store.markUsed(userId, step.getAsLong(), now)) {
            log.info("Staff code accepted for user {}", userId);
            return;
        }
        Optional<UserMfaStore.Failures> failures = store.registerFailure(userId, MAX_WRONG_CODES, now, now.plus(LOCK));
        if (failures.isPresent() && lockedAt(failures.get().lockedUntil(), now)) {
            log.warn("Staff code locked for user {} after {} wrong codes", userId, MAX_WRONG_CODES);
            throw locked();
        }
        log.info("Staff code refused for user {}: wrong or already used", userId);
        throw invalidCode();
    }

    /** A secret that no longer decrypts means the key changed: a 500 for the caller, the reason in the log. */
    private byte[] secretOf(UUID userId, UserMfa mfa) {
        try {
            return cipher.decrypt(userId, mfa.secretEnc());
        } catch (IllegalStateException unreadable) {
            log.error("Staff code secret of user {} cannot be decrypted: was APP_MFA_ENCRYPTION_KEY changed? "
                    + "The user must enrol again", userId);
            throw unreadable;
        }
    }

    private static boolean lockedAt(Instant lockedUntil, Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    private static ApiException alreadyEnrolled() {
        return new ApiException(HttpStatus.CONFLICT, "MFA_ALREADY_ENROLLED", "Un code est déjà configuré pour ce compte.");
    }

    private static ApiException invalidCode() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CODE", "Code incorrect ou expiré.");
    }

    private static ApiException locked() {
        return new ApiException(HttpStatus.LOCKED, "MFA_LOCKED",
                "Trop de codes incorrects. Réessayez dans 15 minutes en vous reconnectant.");
    }
}
