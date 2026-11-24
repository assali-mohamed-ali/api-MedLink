package com.example.apimedlink.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/**
 * What is left of a login whose session could not be opened is cleaned up, and the cleanup never hides the
 * error that made the login fail (review of #90). No Spring context.
 */
class LoginSessionsAbandonTest {

    final LoginSessions sessions = new LoginSessions(
            mock(ClientRegistrationRepository.class),
            mock(OAuth2AuthorizedClientRepository.class),
            "JSESSIONID",
            Duration.ofMinutes(30));

    final MockHttpServletRequest request = new MockHttpServletRequest();
    final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clearTheContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theHalfMadeSessionIsInvalidatedTheContextClearedAndTheCookieDropped() {
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("user", "n/a"));

        sessions.abandon(request, response);

        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getHeader("Set-Cookie")).startsWith("JSESSIONID=;").contains("Max-Age=0");
    }

    @Test
    void noSessionAtAllIsNotAnError() {
        assertThatCode(() -> sessions.abandon(request, response)).doesNotThrowAnyException();

        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=0");
    }

    @Test
    void aSessionThatCannotBeInvalidatedDoesNotHideTheOriginalError() {
        request.setSession(new FailingSession());
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("user", "n/a"));

        assertThatCode(() -> sessions.abandon(request, response)).doesNotThrowAnyException();

        // The rest of the cleanup still happens
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=0");
    }

    /** A session store that refuses to invalidate. */
    static class FailingSession extends MockHttpSession {
        @Override
        public void invalidate() {
            throw new IllegalStateException("session store down");
        }
    }
}
