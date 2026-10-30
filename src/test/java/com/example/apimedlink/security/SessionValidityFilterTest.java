package com.example.apimedlink.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.apimedlink.repository.SessionRevocationStore;
import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import tools.jackson.databind.json.JsonMapper;

/**
 * The branches of the session check that the functional tests cannot reach on demand, above all
 * "Keycloak unreachable" (stopping the shared Keycloak would break the other tests).
 */
class SessionValidityFilterTest {

    final OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
    final SessionRevocationStore revocations = mock(SessionRevocationStore.class);
    final SessionValidityFilter filter =
            new SessionValidityFilter(manager, JsonMapper.builder().build(), revocations);
    final UUID userId = UUID.randomUUID();
    final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/_health");
    final MockHttpServletResponse response = new MockHttpServletResponse();
    final MockHttpSession session = new MockHttpSession();
    final FilterChain chain = mock(FilterChain.class);

    private void loggedInThroughKeycloak() {
        DefaultOAuth2User user = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_PATIENT")), Map.of("sub", userId.toString()), "sub");
        SecurityContextHolder.getContext()
                .setAuthentication(new OAuth2AuthenticationToken(user, user.getAuthorities(), "keycloak"));
        request.setSession(session);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aValidSessionGoesOn() throws Exception {
        loggedInThroughKeycloak();
        when(manager.authorize(any())).thenReturn(mock(OAuth2AuthorizedClient.class));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void aRefusedRenewalEndsTheSession() throws Exception {
        loggedInThroughKeycloak();
        when(manager.authorize(any())).thenThrow(
                new ClientAuthorizationException(new OAuth2Error("invalid_grant", "Account disabled", null), "keycloak"));

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"SESSION_EXPIRED\"");
        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void keycloakUnreachableIs503AndTheSessionIsKept() throws Exception {
        loggedInThroughKeycloak();
        when(manager.authorize(any())).thenThrow(new ClientAuthorizationException(
                new OAuth2Error("invalid_token_response", "Connection refused", null), "keycloak"));

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("\"code\":\"IDENTITY_UNAVAILABLE\"");
        assertThat(session.isInvalid()).as("an outage is not a logout").isFalse();
    }

    @Test
    void aSessionWithoutTokensEnds() throws Exception {
        loggedInThroughKeycloak();
        when(manager.authorize(any())).thenReturn(null);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void aSessionOpenedBeforeTheMarkEndsWithoutAskingKeycloak() throws Exception {
        loggedInThroughKeycloak();
        Instant mark = Instant.parse("2026-10-23T08:00:00Z");
        session.setAttribute(LoginSessions.OPENED_AT, mark.minusSeconds(60));
        when(revocations.revokedAt(userId)).thenReturn(Optional.of(mark));

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        verify(manager, never()).authorize(any());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"SESSION_EXPIRED\"");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void aSessionWithoutAnOpeningTimeEndsOnceTheUserIsMarked() throws Exception {
        loggedInThroughKeycloak();
        when(revocations.revokedAt(userId)).thenReturn(Optional.of(Instant.parse("2026-10-23T08:00:00Z")));
        when(manager.authorize(any())).thenReturn(mock(OAuth2AuthorizedClient.class));

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).as("Keycloak would accept it").isEqualTo(401);
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void aSessionOpenedAfterTheMarkGoesOn() throws Exception {
        loggedInThroughKeycloak();
        Instant mark = Instant.parse("2026-10-23T08:00:00Z");
        session.setAttribute(LoginSessions.OPENED_AT, mark.plusSeconds(60));
        when(revocations.revokedAt(userId)).thenReturn(Optional.of(mark));
        when(manager.authorize(any())).thenReturn(mock(OAuth2AuthorizedClient.class));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void visitorsAndOtherLoginsAreNotChecked() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("someone", null, List.of()));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(manager, never()).authorize(any());
    }
}
