package com.example.apimedlink.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Map;
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
    final SessionValidityFilter filter = new SessionValidityFilter(manager, JsonMapper.builder().build());
    final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/_health");
    final MockHttpServletResponse response = new MockHttpServletResponse();
    final MockHttpSession session = new MockHttpSession();
    final FilterChain chain = mock(FilterChain.class);

    private void loggedInThroughKeycloak() {
        DefaultOAuth2User user = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_PATIENT")), Map.of("sub", "user-1"), "sub");
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
    void visitorsAndOtherLoginsAreNotChecked() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("someone", null, List.of()));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(manager, never()).authorize(any());
    }
}
