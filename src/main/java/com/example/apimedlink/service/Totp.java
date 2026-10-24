package com.example.apimedlink.service;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * MLink-62: the staff code maths, RFC 6238 (TOTP) with what every authenticator app reads by default:
 * HMAC-SHA1, 30-second steps, 6 digits. One step of tolerance either side covers a phone clock a little
 * off. Pure functions: callers pass the time, so tests and {@code MfaService} decide what "now" is.
 */
public final class Totp {

    public static final String ISSUER = "MedLink";
    static final int STEP_SECONDS = 30;
    static final int DIGITS = 6;
    static final int TOLERANCE = 1;

    private static final int SECRET_BYTES = 20; // RFC 4226 §4: 160 bits, the HMAC-SHA1 block output
    private static final String ALGORITHM = "HmacSHA1";
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final Pattern A_CODE = Pattern.compile("\\d{" + DIGITS + "}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    public static byte[] newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    /** RFC 4648 Base32 without "=" padding, as authenticator apps expect it in the otpauth URL. */
    public static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return out.toString();
    }

    /** Reads Base32 as a person may type it: any case, spaces and padding ignored. */
    public static byte[] fromBase32(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : text.toUpperCase(Locale.ROOT).toCharArray()) {
            if (c == ' ' || c == '=') {
                continue;
            }
            int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Not Base32");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    public static long step(Instant at) {
        return Math.floorDiv(at.getEpochSecond(), STEP_SECONDS);
    }

    /** RFC 4226 §5.3: HMAC of the step, dynamic truncation, last {@value #DIGITS} digits. */
    public static String code(byte[] secret, long step) {
        byte[] hash = hmac(secret, ByteBuffer.allocate(Long.BYTES).putLong(step).array());
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        return String.format("%0" + DIGITS + "d", binary % (int) Math.pow(10, DIGITS));
    }

    /**
     * The step whose code this is, within one step of {@code now}; empty when it matches none or is not
     * six digits. The caller refuses a step already used, so a code works once.
     */
    public static OptionalLong matchingStep(byte[] secret, String code, Instant now) {
        if (code == null || !A_CODE.matcher(code).matches()) {
            return OptionalLong.empty();
        }
        byte[] given = code.getBytes(StandardCharsets.US_ASCII);
        long current = step(now);
        OptionalLong match = OptionalLong.empty();
        // Every candidate is computed and compared, so the time taken says nothing about which one matched
        for (long candidate = current - TOLERANCE; candidate <= current + TOLERANCE; candidate++) {
            byte[] expected = code(secret, candidate).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, given) && match.isEmpty()) {
                match = OptionalLong.of(candidate);
            }
        }
        return match;
    }

    /** Key URI format read by authenticator apps: label "MedLink:email", then the secret and our settings. */
    public static String otpauthUrl(String email, String base32Secret) {
        String issuer = encode(ISSUER);
        return "otpauth://totp/" + issuer + ":" + encode(email)
                + "?secret=" + base32Secret
                + "&issuer=" + issuer
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is part of every JDK", e);
        }
    }
}
