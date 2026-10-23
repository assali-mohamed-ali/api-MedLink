package com.example.apimedlink.security;

import com.example.apimedlink.service.KeycloakTokens;
import com.example.apimedlink.service.Login;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Opens the session of an accepted login (MLink-55): a new session id (no fixation), the caller's
 * {@code ROLE_*} authorities in the security context, and the Keycloak tokens in the session as an
 * {@link OAuth2AuthorizedClient}, where {@link SessionValidityFilter} renews them and ends the
 * session once Keycloak refuses (GitHub #28). The browser only gets the session cookie.
 */
@Component
public class LoginSessions {

    /** The client registration in application.properties; the session filter renews tokens through it. */
    static final String REGISTRATION = "keycloak";

    private final ClientRegistrationRepository registrations;
    private final OAuth2AuthorizedClientRepository authorizedClients;
    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();
    private final String sessionCookieName;

    public LoginSessions(ClientRegistrationRepository registrations,
                         OAuth2AuthorizedClientRepository authorizedClients,
                         @Value("${server.servlet.session.cookie.name:JSESSIONID}") String sessionCookieName) {
        this.registrations = registrations;
        this.authorizedClients = authorizedClients;
        this.sessionCookieName = sessionCookieName;
    }

    public void open(Login login, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        } else {
            request.getSession(true);
        }
        request.getSession().removeAttribute(PasswordChangePending.ATTRIBUTE);
        KeycloakTokens tokens = login.tokens();
        List<GrantedAuthority> authorities = login.roles().stream()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        Jwt id = tokens.idToken();
        DefaultOidcUser principal = new DefaultOidcUser(authorities,
                new OidcIdToken(id.getTokenValue(), id.getIssuedAt(), id.getExpiresAt(), id.getClaims()),
                IdTokenClaimNames.SUB);
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(principal, authorities, REGISTRATION);

        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(authentication);
        holder.setContext(context);
        contexts.saveContext(context, request, response);

        Jwt access = tokens.accessToken();
        authorizedClients.saveAuthorizedClient(new OAuth2AuthorizedClient(
                        registrations.findByRegistrationId(REGISTRATION),
                        authentication.getName(),
                        new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                                access.getTokenValue(), access.getIssuedAt(), access.getExpiresAt()),
                        new OAuth2RefreshToken(tokens.refreshToken(), access.getIssuedAt(), tokens.refreshExpiresAt())),
                authentication, request, response);
    }

    /**
     * Ends the caller's session (MLink-54): our session is invalidated, the security context
     * cleared and the browser told to drop the session cookie. Returns the refresh token kept at
     * login, so Keycloak's session can be ended too; null when there is none.
     */
    public String close(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = null;
        Authentication authentication = holder.getContext().getAuthentication();
        if (authentication instanceof OAuth2AuthenticationToken login) {
            OAuth2AuthorizedClient client = authorizedClients.loadAuthorizedClient(REGISTRATION, login, request);
            if (client != null && client.getRefreshToken() != null) {
                refreshToken = client.getRefreshToken().getTokenValue();
            }
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        holder.clearContext();
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(sessionCookieName, "")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .build()
                .toString());
        return refreshToken;
    }
}
