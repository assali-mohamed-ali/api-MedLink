package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.EmailToken;
import com.example.apimedlink.model.EmailToken.Purpose;
import com.example.apimedlink.repository.EmailTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single-use tokens for the links we mail: 32 random bytes (URL-safe Base64) go in the link,
 * only their SHA-256 is stored, valid as long as their {@link Purpose} says.
 */
@Service
@Transactional
public class EmailTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailTokenRepository tokens;

    public EmailTokenService(EmailTokenRepository tokens) {
        this.tokens = tokens;
    }

    /** Stores a new token for the user and returns it in clear, for the link. */
    public String issue(UUID userId, Purpose purpose) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.save(EmailToken.builder()
                .userId(userId)
                .purpose(purpose)
                .tokenHash(hash(raw))
                .expiresAt(Instant.now().plus(purpose.validity()))
                .build());
        return raw;
    }

    /**
     * Marks the token used and returns it. Unknown (or another purpose) → 400 {@code INVALID_TOKEN};
     * expired or already used → 410 {@code TOKEN_EXPIRED}. Joins the caller's transaction, so a
     * failure after this call leaves the token usable.
     */
    public EmailToken consume(String raw, Purpose purpose) {
        EmailToken token = usable(tokens.lockByTokenHash(hash(raw)), purpose);
        token.setUsedAt(Instant.now());
        return token;
    }

    /**
     * Checks that this user's token is usable, without using it (the link keeps working until its owner
     * closes it). Errors as {@link #consume}; another user's token is {@code INVALID_TOKEN} too.
     */
    @Transactional(readOnly = true)
    public void requireValid(String raw, Purpose purpose, UUID userId) {
        usable(tokens.findByTokenHash(hash(raw)).filter(t -> t.getUserId().equals(userId)), purpose);
    }

    private static EmailToken usable(Optional<EmailToken> found, Purpose purpose) {
        EmailToken token = found
                .filter(t -> t.getPurpose() == purpose)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN",
                        "Ce lien n'est pas valide. Vérifiez qu'il est complet ou demandez-en un nouveau."));
        if (token.getUsedAt() != null || !Instant.now().isBefore(token.getExpiresAt())) {
            throw new ApiException(HttpStatus.GONE, "TOKEN_EXPIRED",
                    "Ce lien a expiré ou a déjà été utilisé. Demandez-en un nouveau.");
        }
        return token;
    }

    /** Older links stop working when a new one is sent. */
    public void cancelUnused(UUID userId, Purpose purpose) {
        tokens.markUnusedAsUsed(userId, purpose, Instant.now());
    }

    /** When the user's last token of this purpose was issued, if any. */
    @Transactional(readOnly = true)
    public Instant lastIssuedAt(UUID userId, Purpose purpose) {
        return tokens.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(userId, purpose)
                .map(EmailToken::getCreatedAt)
                .orElse(null);
    }

    static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
