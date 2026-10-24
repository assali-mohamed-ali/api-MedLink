package com.example.apimedlink.service;

import java.util.UUID;

/** MLink-62: encryption of the staff code secret. Stub: the tests in MfaSecretCipherTest come first. */
public final class MfaSecretCipher {

    public MfaSecretCipher(String base64Key) {
    }

    public String encrypt(UUID userId, byte[] secret) {
        throw new UnsupportedOperationException("MLink-62");
    }

    public byte[] decrypt(UUID userId, String stored) {
        throw new UnsupportedOperationException("MLink-62");
    }
}
