package com.example.apimedlink.document;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * MLink-123/125: AES-256-GCM at rest. The stored payload is {@code [version][12-byte IV][ciphertext +
 * tag]}; the associated data is {@code documentId|patientId|keyVersion}, so a payload copied to
 * another row is refused. The key version in the row chooses the key, which makes rotation possible.
 */
public final class AesDocumentCipher implements DocumentCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int VERSION_BYTES = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final int activeVersion;
    private final Map<Integer, SecretKey> keys;

    /** @throws IllegalArgumentException when a key is missing, not base64 or not 32 bytes; never echoes a key */
    public AesDocumentCipher(DocumentProperties.Encryption encryption) {
        if (encryption == null) {
            throw new IllegalArgumentException("app.documents.encryption is missing");
        }
        encryption.validate();
        this.activeVersion = encryption.activeVersion();
        this.keys = encryption.keys().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> new SecretKeySpec(Base64.getDecoder().decode(entry.getValue().trim()), "AES")));
    }

    @Override
    public EncryptedDocument encrypt(byte[] plaintext, UUID documentId, UUID patientId) {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(activeVersion), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData(documentId, patientId, activeVersion));
            byte[] sealed = cipher.doFinal(plaintext);
            byte[] payload = ByteBuffer.allocate(VERSION_BYTES + IV_BYTES + sealed.length)
                    .put((byte) activeVersion)
                    .put(iv)
                    .put(sealed)
                    .array();
            return new EncryptedDocument(payload, activeVersion);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is part of every JDK", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] payload, UUID documentId, UUID patientId, int keyVersion) {
        if (payload == null || payload.length <= VERSION_BYTES + IV_BYTES) {
            throw new DocumentIntegrityException("Le document est illisible.");
        }
        int storedVersion = payload[0] & 0xff;
        if (storedVersion != keyVersion) {
            throw new DocumentIntegrityException("Le document est illisible.");
        }
        SecretKey key = keys.get(keyVersion);
        if (key == null) {
            throw new DocumentIntegrityException("Le document est illisible.");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, payload, VERSION_BYTES, IV_BYTES));
            cipher.updateAAD(associatedData(documentId, patientId, keyVersion));
            return cipher.doFinal(payload, VERSION_BYTES + IV_BYTES, payload.length - VERSION_BYTES - IV_BYTES);
        } catch (GeneralSecurityException | IllegalArgumentException refused) {
            throw new DocumentIntegrityException("Le document est illisible ou a été modifié.", refused);
        }
    }

    private SecretKey key(int version) {
        SecretKey key = keys.get(version);
        if (key == null) {
            // validate() already refused this, so this is a programming error, never a client error
            throw new IllegalStateException("No document encryption key for version " + version);
        }
        return key;
    }

    private static byte[] associatedData(UUID documentId, UUID patientId, int keyVersion) {
        return (documentId + "|" + patientId + "|" + keyVersion).getBytes(StandardCharsets.US_ASCII);
    }
}
