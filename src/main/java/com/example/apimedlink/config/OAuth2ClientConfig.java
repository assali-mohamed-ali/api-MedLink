package com.example.apimedlink.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/** Where the Keycloak tokens live (the server session) and how they are renewed. */
@Configuration
public class OAuth2ClientConfig {

    /**
     * Tokens in the HTTP session, not in a server-wide map: they leave with the session and
     * never outlive a logout.
     */
    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    /**
     * Renews the access token with the refresh token once it is within {@code renewalSkew} of
     * expiring. Keycloak refuses a renewal for a disabled account, ended sessions or an expired
     * SSO session: that is how an open session learns the account changed (GitHub #28).
     */
    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientRepository authorizedClients,
            @Value("${app.session.token-renewal-skew:60s}") Duration renewalSkew) {
        DefaultOAuth2AuthorizedClientManager manager =
                new DefaultOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode()
                .refreshToken(refresh -> refresh.clockSkew(renewalSkew))
                .build());
        return manager;
    }
}
