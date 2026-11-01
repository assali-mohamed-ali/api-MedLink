package com.example.apimedlink.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MLink-123/125: the document bytes are sealed with AES-256-GCM before they leave the API. The
 * patient id and document id are associated data, so a payload copied to another row does not decrypt;
 * the key version stored with the row chooses the key, which makes rotation possible.
 */
class AesDocumentCipherTest {

    /** 32 bytes, base64: a test value only, never a real key. */
    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static final String OTHER_KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));

    static final UUID DOCUMENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OTHER_DOCUMENT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID PATIENT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID OTHER_PATIENT = UUID.fromString("44444444-4444-4444-4444-444444444444");

    static final byte[] PLAINTEXT = "Compte rendu confidentiel, 100 % binaire: \u0000\u00ff".getBytes(
            StandardCharsets.UTF_8);

    final AesDocumentCipher cipher = new AesDocumentCipher(encryption(1, Map.of(1, KEY)));

    @Test
    void whatIsEncryptedDecryptsToTheSameBytes() {
        EncryptedDocument encrypted = cipher.encrypt(PLAINTEXT, DOCUMENT, PATIENT);

        assertThat(encrypted.keyVersion()).isEqualTo(1);
        assertThat(encrypted.bytes()).isNotEqualTo(PLAINTEXT);
        assertThat(cipher.decrypt(encrypted.bytes(), DOCUMENT, PATIENT, encrypted.keyVersion()))
                .isEqualTo(PLAINTEXT);
    }

    @Test
    void aPayloadCopiedToAnotherDocumentDoesNotDecrypt() {
        EncryptedDocument encrypted = cipher.encrypt(PLAINTEXT, DOCUMENT, PATIENT);

        assertThatThrownBy(() -> cipher.decrypt(encrypted.bytes(), OTHER_DOCUMENT, PATIENT, 1))
                .isInstanceOf(DocumentIntegrityException.class);
    }

    @Test
    void aPayloadCopiedToAnotherPatientDoesNotDecrypt() {
        EncryptedDocument encrypted = cipher.encrypt(PLAINTEXT, DOCUMENT, PATIENT);

        assertThatThrownBy(() -> cipher.decrypt(encrypted.bytes(), DOCUMENT, OTHER_PATIENT, 1))
                .isInstanceOf(DocumentIntegrityException.class);
    }

    @Test
    void aChangedByteIsRefused() {
        EncryptedDocument encrypted = cipher.encrypt(PLAINTEXT, DOCUMENT, PATIENT);
        byte[] tampered = encrypted.bytes().clone();
        tampered[tampered.length - 1] ^= 1;

        assertThatThrownBy(() -> cipher.decrypt(tampered, DOCUMENT, PATIENT, 1))
                .isInstanceOf(DocumentIntegrityException.class);
    }

    @Test
    void anUnknownKeyVersionIsRefused() {
        EncryptedDocument encrypted = cipher.encrypt(PLAINTEXT, DOCUMENT, PATIENT);

        assertThatThrownBy(() -> cipher.decrypt(encrypted.bytes(), DOCUMENT, PATIENT, 2))
                .isInstanceOf(DocumentIntegrityException.class);
    }

    @Test
    void aPayloadSealedWithAnotherKeyIsRefused() {
        AesDocumentCipher other = new AesDocumentCipher(encryption(1, Map.of(1, OTHER_KEY)));
        EncryptedDocument encrypted = other.encrypt(PLAINTEXT, DOCUMENT, PATIENT);

        assertThatThrownBy(() -> cipher.decrypt(encrypted.bytes(), DOCUMENT, PATIENT, 1))
                .isInstanceOf(DocumentIntegrityException.class);
    }

    @Test
    void aMissingOrMalformedKeyIsRefusedAtStartupWithoutEchoingIt() {
        assertThatThrownBy(() -> new AesDocumentCipher(encryption(1, Map.of())))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new AesDocumentCipher(encryption(1, Map.of(1, "not base64!"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("not base64!");
    }

    @Test
    void aKeyThatIsNot32BytesIsRefused() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        String longKey = Base64.getEncoder().encodeToString(new byte[33]);

        assertThatThrownBy(() -> new AesDocumentCipher(encryption(1, Map.of(1, shortKey))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(shortKey);
        assertThatThrownBy(() -> new AesDocumentCipher(encryption(1, Map.of(1, longKey))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(longKey);
    }

    @Test
    void theActiveVersionMustHaveAKey() {
        assertThatThrownBy(() -> new AesDocumentCipher(encryption(2, Map.of(1, KEY))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DocumentProperties.Encryption encryption(int activeVersion, Map<Integer, String> keys) {
        return new DocumentProperties.Encryption(activeVersion, keys);
    }
}
