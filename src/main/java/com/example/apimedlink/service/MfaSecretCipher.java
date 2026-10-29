package com.example.apimedlink.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MLink-62: the staff code secret at rest, AES-256-GCM. Stored as base64(IV ‖ ciphertext ‖ tag), a new
 * 12-byte IV each time. The user id is the associated data: a secret copied to another user's row does not
 * decrypt. The key ({@code app.mfa.encryption-key}, 32 bytes in base64) comes from the environment and is
 * checked when the application starts; it is never logged or echoed.
 */
@Component
public final class MfaSecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    /** @throws IllegalArgumentException when the key is missing, not base64 or not 32 bytes */
    public MfaSecretCipher(@Value("${app.mfa.encryption-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("app.mfa.encryption-key is missing (APP_MFA_ENCRYPTION_KEY)");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalArgumentException("app.mfa.encryption-key must be base64 (32 bytes)");
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalArgumentException("app.mfa.encryption-key must be 32 bytes once base64-decoded");
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public String encrypt(UUID userId, byte[] secret) {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData(userId));
            byte[] sealed = cipher.doFinal(secret);
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(IV_BYTES + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is part of every JDK", e);
        }
    }

    /** @throws IllegalStateException when the text was not sealed by this key for this user, or was changed */
    public byte[] decrypt(UUID userId, String stored) {
        try {
            byte[] bytes = Base64.getDecoder().decode(stored);
            if (bytes.length <= IV_BYTES) {
                throw new IllegalStateException("Staff code secret cannot be decrypted");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
            cipher.updateAAD(associatedData(userId));
            return cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Staff code secret cannot be decrypted", e);
        }
    }

    private static byte[] associatedData(UUID userId) {
        return userId.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
