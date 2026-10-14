package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.EmailToken;
import com.example.apimedlink.model.EmailToken.Purpose;
import com.example.apimedlink.repository.EmailTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single-use tokens for the links we mail: 32 random bytes (URL-safe Base64) go in the link,
 * only their SHA-256 is stored, valid 24 hours.
 */
@Service
@Transactional
public class EmailTokenService {

    static final Duration VALIDITY = Duration.ofHours(24);

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
                .expiresAt(Instant.now().plus(VALIDITY))
                .build());
        return raw;
    }

    /**
     * Marks the token used and returns it. Unknown (or another purpose) → 400 {@code INVALID_TOKEN};
     * expired or already used → 410 {@code TOKEN_EXPIRED}. Joins the caller's transaction, so a
     * failure after this call leaves the token usable.
     */
    public EmailToken consume(String raw, Purpose purpose) {
        EmailToken token = tokens.lockByTokenHash(hash(raw))
                .filter(t -> t.getPurpose() == purpose)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN",
                        "Ce lien n'est pas valide. Vérifiez qu'il est complet ou demandez-en un nouveau."));
        Instant now = Instant.now();
        if (token.getUsedAt() != null || !now.isBefore(token.getExpiresAt())) {
            throw new ApiException(HttpStatus.GONE, "TOKEN_EXPIRED",
                    "Ce lien a expiré ou a déjà été utilisé. Demandez-en un nouveau.");
        }
        token.setUsedAt(now);
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
