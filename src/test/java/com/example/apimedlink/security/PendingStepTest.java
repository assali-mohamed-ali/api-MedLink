package com.example.apimedlink.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.MovableClock;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The step between "Keycloak accepted the password" and "the session opens": a forced password change
 * (MLink-88) or the staff code (MLink-62). What a functional test cannot wait for: the five minutes running out.
 */
class PendingStepTest {

    static final Instant T0 = Instant.parse("2026-10-25T10:00:00Z");

    static final class TestStep extends PendingStep<String> {
        TestStep(Clock clock) {
            super(clock);
        }
    }

    final MovableClock clock = new MovableClock(T0);
    final TestStep step = new TestStep(clock);
    final MockHttpServletRequest request = new MockHttpServletRequest();

    @AfterEach
    void forgetTheLogin() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void whatWasStartedIsReadBackWithinFiveMinutes() {
        step.start("value", request);
        clock.set(T0.plus(Duration.ofMinutes(5)));

        assertThat(step.current(request)).contains("value");
    }

    @Test
    void afterFiveMinutesItIsGoneWithItsSession() {
        step.start("value", request);
        HttpSession session = request.getSession(false);
        clock.set(T0.plus(Duration.ofMinutes(5)).plusSeconds(1));

        assertThat(step.current(request)).isEmpty();
        assertThat(((MockHttpSession) session).isInvalid()).isTrue();
    }

    @Test
    void theSessionItselfEndsAfterFiveMinutesIdle() {
        step.start("value", request);

        assertThat(request.getSession(false).getMaxInactiveInterval()).isEqualTo(300);
    }

    @Test
    void startingDropsThePreviousSessionAndAnyLogin() {
        MockHttpSession previous = (MockHttpSession) request.getSession(true);
        previous.setAttribute("something", "from before");
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("someone", null));

        step.start("value", request);

        assertThat(previous.isInvalid()).isTrue();
        assertThat(request.getSession(false)).isNotSameAs(previous);
        assertThat(request.getSession(false).getAttribute("something")).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void nothingStartedIsNothingPending() {
        assertThat(step.current(request)).isEmpty();
        request.getSession(true);
        assertThat(step.current(request)).isEmpty();
    }

    @Test
    void clearEndsItAndItsSession() {
        step.start("value", request);
        MockHttpSession session = (MockHttpSession) request.getSession(false);

        step.clear(request);

        assertThat(session.isInvalid()).isTrue();
        assertThat(step.current(request)).isEmpty();
    }

    @Test
    void twoKindsOfStepDoNotSeeEachOther() {
        final class OtherStep extends PendingStep<String> {
            OtherStep(Clock clock) {
                super(clock);
            }
        }
        step.start("value", request);

        assertThat(new OtherStep(clock).current(request)).isEmpty();
    }
}
