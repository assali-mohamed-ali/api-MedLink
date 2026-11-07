package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.apimedlink.MovableClock;
import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.User;
import com.example.apimedlink.model.UserMfa;
import com.example.apimedlink.repository.UserMfaStore;
import com.example.apimedlink.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

/**
 * MLink-62/63, the staff code rules on a real database: enrolment stores a sealed secret, the first good code
 * enables it, a code works once, and 5 wrong codes lock the code step for 15 minutes. What a functional test
 * cannot wait for: the lock running out (the clock is moved here).
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
@Import(TestcontainersConfiguration.class)
class MfaServiceTest {

    static final Instant T0 = Instant.parse("2026-10-25T10:00:00Z");

    @Autowired
    UserMfaStore store;

    @Autowired
    MfaSecretCipher cipher;

    @Autowired
    UserRepository users;

    final MovableClock clock = new MovableClock(T0);

    private MfaService service() {
        return new MfaService(store, cipher, clock);
    }

    private UUID aUser() {
        UUID id = UUID.randomUUID();
        users.save(User.builder().id(id).username(id + "@medlink.local").email(id + "@medlink.local").build());
        return id;
    }

    private String codeNow(MfaService.Enrolment enrolment) {
        return Totp.code(Totp.fromBase32(enrolment.secret()), Totp.step(clock.instant()));
    }

    private static void assertRefused(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }

    @Test
    void enrolmentGivesTheSecretAndItsUrlAndStoresItSealed() {
        UUID id = aUser();

        MfaService.Enrolment enrolment = service().startEnrolment(id, "practitioner@medlink.local");

        assertThat(enrolment.secret()).matches("[A-Z2-7]{32}");
        assertThat(enrolment.otpauthUrl()).isEqualTo(Totp.otpauthUrl("practitioner@medlink.local", enrolment.secret()));
        UserMfa row = store.find(id).orElseThrow();
        assertThat(row.secretEnc()).doesNotContain(enrolment.secret());
        assertThat(cipher.decrypt(id, row.secretEnc())).isEqualTo(Totp.fromBase32(enrolment.secret()));
        assertThat(service().isEnrolled(id)).isFalse();
    }

    @Test
    void theFirstGoodCodeEnablesTheCode() {
        UUID id = aUser();
        MfaService.Enrolment enrolment = service().startEnrolment(id, "a@medlink.local");

        assertThatCode(() -> service().verify(id, codeNow(enrolment))).doesNotThrowAnyException();

        assertThat(service().isEnrolled(id)).isTrue();
    }

    @Test
    void onceEnabledNoNewSetupIsAccepted() {
        UUID id = aUser();
        MfaService.Enrolment enrolment = service().startEnrolment(id, "a@medlink.local");
        service().verify(id, codeNow(enrolment));

        assertRefused(() -> service().startEnrolment(id, "a@medlink.local"), HttpStatus.CONFLICT, "MFA_ALREADY_ENROLLED");
    }

    @Test
    void aCodeWorksOnlyOnce() {
        UUID id = aUser();
        MfaService.Enrolment enrolment = service().startEnrolment(id, "a@medlink.local");
        String code = codeNow(enrolment);
        service().verify(id, code);

        assertRefused(() -> service().verify(id, code), HttpStatus.UNAUTHORIZED, "INVALID_CODE");
    }

    @Test
    void aWrongCodeIsRefusedAndCounted() {
        UUID id = aUser();
        MfaService.Enrolment enrolment = service().startEnrolment(id, "a@medlink.local");

        assertRefused(() -> service().verify(id, wrong(codeNow(enrolment))), HttpStatus.UNAUTHORIZED, "INVALID_CODE");

        assertThat(store.find(id).orElseThrow().failedAttempts()).isEqualTo(1);
    }

    @Test
    void theFifthWrongCodeLocksAndThenEvenTheRightCodeIsRefused() {
        UUID id = aUser();
        MfaService.Enrolment enrolment = service().startEnrolment(id, "a@medlink.local");
        String wrong = wrong(codeNow(enrolment));
        for (int i = 1; i <= 4; i++) {
            assertRefused(() -> service().verify(id, wrong), HttpStatus.UNAUTHORIZED, "INVALID_CODE");
        }

        assertRefused(() -> service().verify(id, wrong), HttpStatus.LOCKED, "MFA_LOCKED");
        assertRefused(() -> service().verify(id, codeNow(enrolment)), HttpStatus.LOCKED, "MFA_LOCKED");
        assertThat(service().isEnrolled(id)).isFalse();
    }

    @Test
    void theLockEndsAfterFifteenMinutes() {
        UUID id = aUser();
        MfaService.Enrolment enrolment = service().startEnrolment(id, "a@medlink.local");
        String wrong = wrong(codeNow(enrolment));
        for (int i = 1; i <= 5; i++) {
            assertThatThrownBy(() -> service().verify(id, wrong)).isInstanceOf(ApiException.class);
        }

        clock.set(T0.plus(Duration.ofMinutes(15)).minusSeconds(1));
        assertRefused(() -> service().verify(id, codeNow(enrolment)), HttpStatus.LOCKED, "MFA_LOCKED");

        clock.set(T0.plus(Duration.ofMinutes(15)));
        assertThatCode(() -> service().verify(id, codeNow(enrolment))).doesNotThrowAnyException();
        assertThat(store.find(id).orElseThrow().failedAttempts()).isZero();
    }

    @Test
    void aSecretSealedWithAnotherKeyFailsLoudlyNamingOnlyTheUser(CapturedOutput output) {
        UUID id = aUser();
        byte[] secret = Totp.newSecret();
        MfaSecretCipher otherKey = new MfaSecretCipher(Base64.getEncoder().encodeToString(new byte[] {
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28,
                29, 30, 31, 32}));
        store.saveSecret(id, otherKey.encrypt(id, secret));

        assertThatThrownBy(() -> service().verify(id, "123456")).isInstanceOf(IllegalStateException.class);

        // The key changed (an operations mistake): the log says whose code and why, never the secret
        assertThat(output).contains("user " + id).contains("APP_MFA_ENCRYPTION_KEY").doesNotContain(Totp.base32(secret));
    }

    @Test
    void withoutASetupThereIsNoCodeToCheck() {
        UUID id = aUser();

        assertRefused(() -> service().verify(id, "123456"), HttpStatus.UNAUTHORIZED, "INVALID_CODE");
    }

    /** Another 6-digit code than {@code code}. */
    private static String wrong(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }
}
