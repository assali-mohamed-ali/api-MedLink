package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * MLink-62: the staff code maths. RFC 6238 (TOTP: HMAC-SHA1, 30-second steps, 6 digits) and RFC 4648 (Base32,
 * what authenticator apps read from the QR code), checked against the vectors the RFCs publish.
 */
class TotpTest {

    /** RFC 6238 appendix B: the SHA1 key is the ASCII string "12345678901234567890". */
    static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    /** RFC 6238 appendix B prints 8 digits; ours are the last 6 of the same truncated value. */
    @ParameterizedTest
    @CsvSource({
            "59,          287082",
            "1111111109,  081804",
            "1111111111,  050471",
            "1234567890,  005924",
            "2000000000,  279037",
            "20000000000, 353130"})
    void theCodesAreTheRfc6238Vectors(long epochSecond, String expected) {
        long step = Totp.step(Instant.ofEpochSecond(epochSecond));

        assertThat(Totp.code(RFC_SECRET, step)).isEqualTo(expected);
    }

    @Test
    void aStepLastsThirtySeconds() {
        assertThat(Totp.step(Instant.ofEpochSecond(0))).isZero();
        assertThat(Totp.step(Instant.ofEpochSecond(29))).isZero();
        assertThat(Totp.step(Instant.ofEpochSecond(30))).isEqualTo(1);
    }

    @Test
    void theCurrentCodeMatchesAndSaysWhichStep() {
        Instant now = Instant.ofEpochSecond(1111111111);
        long step = Totp.step(now);

        assertThat(Totp.matchingStep(RFC_SECRET, "050471", now)).isEqualTo(OptionalLong.of(step));
    }

    @Test
    void oneStepOfToleranceEitherSide() {
        Instant now = Instant.ofEpochSecond(1111111111);
        long step = Totp.step(now);

        assertThat(Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now))
                .isEqualTo(OptionalLong.of(step - 1));
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, step + 1), now))
                .isEqualTo(OptionalLong.of(step + 1));
    }

    @Test
    void twoStepsAwayIsRefused() {
        Instant now = Instant.ofEpochSecond(1111111111);
        long step = Totp.step(now);

        assertThat(Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, step - 2), now)).isEmpty();
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, step + 2), now)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "05047", "0504711", "05o471", " 050471", "050 471", "-50471"})
    void onlySixDigitsAreACode(String code) {
        Instant now = Instant.ofEpochSecond(1111111111);

        assertThat(Totp.matchingStep(RFC_SECRET, code, now)).isEmpty();
    }

    @Test
    void noCodeIsNoMatch() {
        assertThat(Totp.matchingStep(RFC_SECRET, null, Instant.ofEpochSecond(1111111111))).isEmpty();
    }

    /** RFC 4648 §10, without the "=" padding: authenticator apps accept it and our secrets never need it. */
    @ParameterizedTest
    @CsvSource({
            "f,      MY",
            "fo,     MZXQ",
            "foo,    MZXW6",
            "foob,   MZXW6YQ",
            "fooba,  MZXW6YTB",
            "foobar, MZXW6YTBOI"})
    void base32IsRfc4648(String text, String expected) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);

        assertThat(Totp.base32(bytes)).isEqualTo(expected);
        assertThat(Totp.fromBase32(expected)).isEqualTo(bytes);
    }

    @Test
    void base32ReadsWhatAPersonMightType() {
        assertThat(Totp.fromBase32("mzxw 6ytb oi==")).isEqualTo("foobar".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void aNewSecretIsTwentyRandomBytesAnd32Characters() {
        byte[] one = Totp.newSecret();
        byte[] two = Totp.newSecret();

        assertThat(one).hasSize(20);
        assertThat(one).isNotEqualTo(two);
        assertThat(Totp.base32(one)).hasSize(32).matches("[A-Z2-7]{32}");
        assertThat(Totp.fromBase32(Totp.base32(one))).isEqualTo(one);
    }

    @Test
    void theOtpauthUrlIsWhatAuthenticatorAppsRead() {
        assertThat(Totp.otpauthUrl("staff.dev@medlink.local", "MZXW6YTBOI"))
                .isEqualTo("otpauth://totp/MedLink:staff.dev%40medlink.local"
                        + "?secret=MZXW6YTBOI&issuer=MedLink&algorithm=SHA1&digits=6&period=30");
    }
}
