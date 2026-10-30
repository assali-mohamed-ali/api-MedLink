package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.EmailToken;
import com.example.apimedlink.model.EmailToken.Purpose;
import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.EmailTokenRepository;
import com.example.apimedlink.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EmailTokenServiceTest {

    @Autowired
    EmailTokenService service;

    @Autowired
    EmailTokenRepository tokens;

    @Autowired
    UserRepository users;

    UUID userId;

    @BeforeEach
    void user() {
        userId = UUID.randomUUID();
        users.save(User.builder().id(userId).username(userId + "@example.com").email(userId + "@example.com").build());
    }

    @Test
    void onlyTheHashIsStoredAndTheTokenLasts24Hours() {
        String raw = service.issue(userId, Purpose.VERIFY_EMAIL);

        List<EmailToken> rows = tokens.findAll().stream().filter(t -> t.getUserId().equals(userId)).toList();
        assertThat(rows).hasSize(1);
        EmailToken row = rows.get(0);
        assertThat(raw).matches("[A-Za-z0-9_-]{43}"); // 32 bytes, URL-safe Base64, no padding
        assertThat(row.getTokenHash()).isEqualTo(EmailTokenService.hash(raw)).matches("[0-9a-f]{64}").isNotEqualTo(raw);
        assertThat(row.getPurpose()).isEqualTo(Purpose.VERIFY_EMAIL);
        assertThat(Duration.between(Instant.now(), row.getExpiresAt())).isBetween(Duration.ofHours(23), Duration.ofHours(24));
        assertThat(row.getUsedAt()).isNull();
    }

    @Test
    void aValidTokenIsConsumedOnce() {
        String raw = service.issue(userId, Purpose.VERIFY_EMAIL);

        EmailToken used = service.consume(raw, Purpose.VERIFY_EMAIL);

        assertThat(used.getUserId()).isEqualTo(userId);
        assertThat(tokens.findById(used.getId()).orElseThrow().getUsedAt()).isNotNull();
        assertError(() -> service.consume(raw, Purpose.VERIFY_EMAIL), HttpStatus.GONE, "TOKEN_EXPIRED");
    }

    @Test
    void anUnknownTokenIsInvalid() {
        assertError(() -> service.consume("not-a-token", Purpose.VERIFY_EMAIL), HttpStatus.BAD_REQUEST, "INVALID_TOKEN");
    }

    @Test
    void aTokenOfAnotherPurposeIsInvalid() {
        String raw = service.issue(userId, Purpose.ACTIVATE_ACCOUNT);

        assertError(() -> service.consume(raw, Purpose.VERIFY_EMAIL), HttpStatus.BAD_REQUEST, "INVALID_TOKEN");
    }

    @Test
    void anExpiredTokenIsGone() {
        String raw = service.issue(userId, Purpose.VERIFY_EMAIL);
        EmailToken row = tokens.findByTokenHash(EmailTokenService.hash(raw)).orElseThrow();
        row.setExpiresAt(Instant.now().minusSeconds(1));
        tokens.save(row);

        assertError(() -> service.consume(raw, Purpose.VERIFY_EMAIL), HttpStatus.GONE, "TOKEN_EXPIRED");
    }

    @Test
    void cancellingLeavesOlderTokensUnusable() {
        String old = service.issue(userId, Purpose.VERIFY_EMAIL);

        service.cancelUnused(userId, Purpose.VERIFY_EMAIL);

        assertError(() -> service.consume(old, Purpose.VERIFY_EMAIL), HttpStatus.GONE, "TOKEN_EXPIRED");
    }

    // ---- the staff code's first-enrolment link (review of #97, MLink-62) ----------------------------

    @Test
    void anEnrolmentTokenLasts30Minutes() {
        String raw = service.issue(userId, Purpose.MFA_ENROLMENT);

        EmailToken row = tokens.findByTokenHash(EmailTokenService.hash(raw)).orElseThrow();
        assertThat(row.getPurpose()).isEqualTo(Purpose.MFA_ENROLMENT);
        assertThat(Duration.between(Instant.now(), row.getExpiresAt()))
                .isBetween(Duration.ofMinutes(29), Duration.ofMinutes(30));
    }

    @Test
    void requireValidChecksWithoutUsingTheToken() {
        String raw = service.issue(userId, Purpose.MFA_ENROLMENT);

        service.requireValid(raw, Purpose.MFA_ENROLMENT, userId);
        service.requireValid(raw, Purpose.MFA_ENROLMENT, userId);

        assertThat(tokens.findByTokenHash(EmailTokenService.hash(raw)).orElseThrow().getUsedAt()).isNull();
    }

    @Test
    void requireValidRefusesAnotherUsersToken() {
        String raw = service.issue(userId, Purpose.MFA_ENROLMENT);
        UUID someoneElse = UUID.randomUUID();

        assertError(() -> service.requireValid(raw, Purpose.MFA_ENROLMENT, someoneElse),
                HttpStatus.BAD_REQUEST, "INVALID_TOKEN");
    }

    @Test
    void requireValidRefusesAnUnknownTokenOrAnotherPurpose() {
        String verify = service.issue(userId, Purpose.VERIFY_EMAIL);

        assertError(() -> service.requireValid("not-a-token", Purpose.MFA_ENROLMENT, userId),
                HttpStatus.BAD_REQUEST, "INVALID_TOKEN");
        assertError(() -> service.requireValid(verify, Purpose.MFA_ENROLMENT, userId),
                HttpStatus.BAD_REQUEST, "INVALID_TOKEN");
    }

    @Test
    void requireValidRefusesAnExpiredOrClosedToken() {
        String expired = service.issue(userId, Purpose.MFA_ENROLMENT);
        EmailToken row = tokens.findByTokenHash(EmailTokenService.hash(expired)).orElseThrow();
        row.setExpiresAt(Instant.now().minusSeconds(1));
        tokens.save(row);
        assertError(() -> service.requireValid(expired, Purpose.MFA_ENROLMENT, userId),
                HttpStatus.GONE, "TOKEN_EXPIRED");

        String closed = service.issue(userId, Purpose.MFA_ENROLMENT);
        service.cancelUnused(userId, Purpose.MFA_ENROLMENT);
        assertError(() -> service.requireValid(closed, Purpose.MFA_ENROLMENT, userId),
                HttpStatus.GONE, "TOKEN_EXPIRED");
    }

    private static void assertError(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }
}
