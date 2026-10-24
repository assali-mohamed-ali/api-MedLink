package com.example.apimedlink.service;

import java.time.Instant;
import java.util.OptionalLong;

/** MLink-62: staff code maths (RFC 6238). Stub: the tests in TotpTest come first. */
public final class Totp {

    private Totp() {}

    public static byte[] newSecret() {
        throw new UnsupportedOperationException("MLink-62");
    }

    public static String base32(byte[] bytes) {
        throw new UnsupportedOperationException("MLink-62");
    }

    public static byte[] fromBase32(String text) {
        throw new UnsupportedOperationException("MLink-62");
    }

    public static long step(Instant at) {
        throw new UnsupportedOperationException("MLink-62");
    }

    public static String code(byte[] secret, long step) {
        throw new UnsupportedOperationException("MLink-62");
    }

    public static OptionalLong matchingStep(byte[] secret, String code, Instant now) {
        throw new UnsupportedOperationException("MLink-62");
    }

    public static String otpauthUrl(String email, String base32Secret) {
        throw new UnsupportedOperationException("MLink-62");
    }
}
