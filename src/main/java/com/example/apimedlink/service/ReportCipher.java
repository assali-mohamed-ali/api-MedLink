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
 * MLink-32: the consultation report content at rest, AES-256-GCM, built like {@link MfaSecretCipher}. Stored as
 * base64(IV ‖ ciphertext ‖ tag), a new 12-byte IV each time. The report id and the patient id are the associated
 * data: a sealed text copied to another report or another patient does not decrypt. Its own key
 * ({@code app.report.encryption-key}, 32 bytes in base64, never the staff code key), checked at start-up. Neither
 * the key nor any content is ever logged or put in a message.
 */
@Component
public final class ReportCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    /** @throws IllegalArgumentException when the key is missing, not base64 or not 32 bytes */
    public ReportCipher(@Value("${app.report.encryption-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("app.report.encryption-key is missing (APP_REPORT_ENCRYPTION_KEY)");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalArgumentException("app.report.encryption-key must be base64 (32 bytes)");
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalArgumentException("app.report.encryption-key must be 32 bytes once base64-decoded");
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public String encrypt(UUID reportId, UUID patientId, String content) {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData(reportId, patientId));
            byte[] sealed = cipher.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(IV_BYTES + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is part of every JDK", e);
        }
    }

    /** @throws IllegalStateException when the text was not sealed by this key for this report and patient, or was changed */
    public String decrypt(UUID reportId, UUID patientId, String stored) {
        try {
            byte[] bytes = Base64.getDecoder().decode(stored);
            if (bytes.length <= IV_BYTES) {
                throw new IllegalStateException("Consultation report cannot be decrypted");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
            cipher.updateAAD(associatedData(reportId, patientId));
            return new String(cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Consultation report cannot be decrypted", e);
        }
    }

    private static byte[] associatedData(UUID reportId, UUID patientId) {
        return (reportId + ":" + patientId).getBytes(StandardCharsets.US_ASCII);
    }
}
