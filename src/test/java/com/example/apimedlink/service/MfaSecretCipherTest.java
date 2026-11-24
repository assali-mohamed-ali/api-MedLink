package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * MLink-62: the staff code secret is stored encrypted (AES-256-GCM). A database dump alone gives no secret,
 * a secret copied to another user's row does not decrypt, and a changed byte is refused.
 */
class MfaSecretCipherTest {

    /** 32 bytes, base64: a test value only, never a real key. */
    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    final MfaSecretCipher cipher = new MfaSecretCipher(KEY);
    final byte[] secret = Totp.newSecret();

    @Test
    void whatIsEncryptedDecryptsForTheSameUser() {
        String stored = cipher.encrypt(USER, secret);

        assertThat(cipher.decrypt(USER, stored)).isEqualTo(secret);
    }

    @Test
    void theStoredTextIsNeitherTheSecretNorItsBase32() {
        String stored = cipher.encrypt(USER, secret);

        assertThat(stored).doesNotContain(Totp.base32(secret));
        // Latin-1 maps each byte to one char, so "contains" is a byte-sequence check
        assertThat(new String(Base64.getDecoder().decode(stored), StandardCharsets.ISO_8859_1))
                .doesNotContain(new String(secret, StandardCharsets.ISO_8859_1));
    }

    @Test
    void eachEncryptionUsesANewIv() {
        assertThat(cipher.encrypt(USER, secret)).isNotEqualTo(cipher.encrypt(USER, secret));
    }

    @Test
    void aSecretCopiedToAnotherUserDoesNotDecrypt() {
        String stored = cipher.encrypt(USER, secret);

        assertThatThrownBy(() -> cipher.decrypt(OTHER, stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aChangedByteIsRefused() {
        byte[] stored = Base64.getDecoder().decode(cipher.encrypt(USER, secret));
        stored[stored.length - 1] ^= 1;
        String tampered = Base64.getEncoder().encodeToString(stored);

        assertThatThrownBy(() -> cipher.decrypt(USER, tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anotherKeyCannotDecrypt() {
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        String stored = cipher.encrypt(USER, secret);

        assertThatThrownBy(() -> new MfaSecretCipher(Base64.getEncoder().encodeToString(otherKey)).decrypt(USER, stored))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not base64!", "AAAA"})
    void storedTextThatIsNotOursIsRefused(String stored) {
        assertThatThrownBy(() -> cipher.decrypt(USER, stored)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void noKeyIsRefusedAtStartup(String key) {
        assertThatThrownBy(() -> new MfaSecretCipher(key))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.mfa.encryption-key");
    }

    @Test
    void aKeyThatIsNot32BytesIsRefusedAtStartup() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new MfaSecretCipher(shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes")
                .hasMessageNotContaining(shortKey);
    }

    @Test
    void aKeyThatIsNotBase64IsRefusedWithoutEchoingIt() {
        String notBase64 = "this-is-not-base64-but-a-passphrase!";

        assertThatThrownBy(() -> new MfaSecretCipher(notBase64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(notBase64);
    }
}
