package com.example.apimedlink.security;

import com.example.apimedlink.exception.ErrorBody;
import com.example.apimedlink.repository.SessionRevocationStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every call of a logged-in user checks that Keycloak still accepts the account (GitHub #28).
 * While the access token is fresh nothing is called; once it expires it is renewed, and a
 * refused renewal (account disabled, signed out, password reset, SSO session over) ends our
 * session: 401 {@code SESSION_EXPIRED}. Keycloak unreachable: 503, the session is kept.
 *
 * <p>Before Keycloak, the API's own rule (review of PR #89): a session opened at or before the
 * user's revocation mark (a password change, a staff reset) ends, even if Keycloak still accepts it.
 */
public class SessionValidityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionValidityFilter.class);
    private static final String KEYCLOAK_REFUSED = "Keycloak no longer accepts it";

    private final OAuth2AuthorizedClientManager authorizedClients;
    private final JsonMapper json;
    private final SessionRevocationStore revocations;

    public SessionValidityFilter(OAuth2AuthorizedClientManager authorizedClients, JsonMapper json,
                                 SessionRevocationStore revocations) {
        this.authorizedClients = authorizedClients;
        this.json = json;
        this.revocations = revocations;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof OAuth2AuthenticationToken login)) {
            chain.doFilter(request, response);
            return;
        }
        if (revoked(request, login)) {
            endSession(request, response, login.getName(), "its sessions were revoked (password change or reset)");
            return;
        }
        OAuth2AuthorizedClient client;
        try {
            client = authorizedClients.authorize(OAuth2AuthorizeRequest
                    .withClientRegistrationId(login.getAuthorizedClientRegistrationId())
                    .principal(login)
                    .attribute(HttpServletRequest.class.getName(), request)
                    .attribute(HttpServletResponse.class.getName(), response)
                    .build());
        } catch (OAuth2AuthorizationException e) {
            if (OAuth2ErrorCodes.INVALID_GRANT.equals(e.getError().getErrorCode())) {
                endSession(request, response, login.getName(), KEYCLOAK_REFUSED);
            } else {
                log.warn("Session check could not reach Keycloak ({})", e.getError().getErrorCode());
                write(response, HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_UNAVAILABLE",
                        "Le service d'identité est momentanément indisponible. Réessayez dans un instant.");
            }
            return;
        }
        if (client == null) { // no tokens in this session (e.g. a session from before a restart)
            endSession(request, response, login.getName(), KEYCLOAK_REFUSED);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * The user's mark is read on every call (one primary-key read), so a revocation holds at once. A
     * session without an opening time was not opened by {@link LoginSessions}: never after the mark.
     */
    private boolean revoked(HttpServletRequest request, OAuth2AuthenticationToken login) {
        UUID userId;
        try {
            userId = UUID.fromString(login.getName());
        } catch (IllegalArgumentException notAKeycloakId) {
            return false; // no revocation can name it
        }
        Optional<Instant> mark = revocations.revokedAt(userId);
        if (mark.isEmpty()) {
            return false;
        }
        HttpSession session = request.getSession(false);
        Object opened = session == null ? null : session.getAttribute(LoginSessions.OPENED_AT);
        return !(opened instanceof Instant at) || !at.isAfter(mark.get());
    }

    private void endSession(HttpServletRequest request, HttpServletResponse response, String user, String why)
            throws IOException {
        log.info("Session ended for user {}: {}", user, why);
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        write(response, HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED",
                "Votre session a expiré. Reconnectez-vous.");
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(ErrorBody.of(status, code, message, null)));
    }
}
