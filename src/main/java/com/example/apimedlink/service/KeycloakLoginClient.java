package com.example.apimedlink.service;

import com.example.apimedlink.config.KeycloakProperties;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.exception.KeycloakUnavailableException;
import com.example.apimedlink.exception.PasswordChangeRequiredException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * The login's door to Keycloak (MLink-55): the password grant as the confidential client
 * {@code medlink-bff}, server to server, and the end of a Keycloak session. Tokens are checked
 * (signature through Keycloak's keys, issuer, issued for {@code medlink-bff}) before anyone reads
 * them. Never logs a password or a token.
 *
 * <p>How Keycloak answers the password grant (checked against Keycloak 26.7.5, see report.md):
 * a wrong password, an unknown email and a locked account all give 400 {@code invalid_grant}
 * "Invalid user credentials"; a disabled account gives "Account disabled", and only with the
 * right password; an unverified email is accepted.
 */
@Service
public class KeycloakLoginClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakLoginClient.class);
    private static final String ACCOUNT_DISABLED = "Account disabled";

    private final RestClient http;
    private final KeycloakProperties keycloak;
    private final JwtDecoder tokens;
    private final String tokenPath;
    private final String logoutPath;

    public KeycloakLoginClient(RestClient keycloakRestClient, KeycloakProperties keycloak) {
        this.http = keycloakRestClient;
        this.keycloak = keycloak;
        String openIdConnect = "/realms/" + keycloak.realm() + "/protocol/openid-connect";
        this.tokenPath = openIdConnect + "/token";
        this.logoutPath = openIdConnect + "/logout";
        this.tokens = decoder(keycloak, keycloak.internalUrl() + openIdConnect + "/certs");
    }

    /** Keycloak's keys, fetched lazily (the API starts even when Keycloak is down), with short timeouts. */
    private static JwtDecoder decoder(KeycloakProperties keycloak, String jwkSetUri) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .restOperations(new RestTemplate(factory))
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(keycloak.publicUrl() + "/realms/" + keycloak.realm()),
                new JwtClaimValidator<String>("azp", keycloak.clientId()::equals)));
        return decoder;
    }

    /**
     * Exchanges an email and a password for tokens.
     *
     * @throws ApiException 401 {@code INVALID_CREDENTIALS} (wrong password, unknown email, locked),
     *     403 {@code ACCOUNT_DISABLED}; {@link KeycloakUnavailableException} when Keycloak cannot answer
     */
    public KeycloakTokens passwordGrant(String email, String password) {
        MultiValueMap<String, String> form = clientForm();
        form.add("grant_type", "password");
        form.add("username", email);
        form.add("password", password);
        form.add("scope", "openid");
        TokenResponse response;
        try {
            response = http.post().uri(tokenPath)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((request, answer) -> {
                        int status = answer.getStatusCode().value();
                        if (status == 200) {
                            return answer.bodyTo(TokenResponse.class);
                        }
                        if (status == 400 || status == 401) {
                            throw refusal(answer.bodyTo(TokenError.class));
                        }
                        throw new KeycloakUnavailableException("password grant: HTTP " + status);
                    });
        } catch (RestClientException e) {
            throw new KeycloakUnavailableException("password grant: " + e.getClass().getSimpleName());
        }
        if (response == null || response.accessToken() == null || response.idToken() == null) {
            throw new KeycloakUnavailableException("password grant: incomplete token response");
        }
        Instant now = Instant.now();
        return new KeycloakTokens(
                check(response.accessToken()),
                check(response.idToken()),
                response.refreshToken(),
                response.refreshExpiresIn() > 0 ? now.plusSeconds(response.refreshExpiresIn()) : null);
    }

    /**
     * Ends the Keycloak session these tokens belong to. Best effort: a failure is only logged,
     * the caller goes on (our own session is what protects the API).
     */
    public void endSession(String refreshToken) {
        if (refreshToken == null) {
            return;
        }
        MultiValueMap<String, String> form = clientForm();
        form.add("refresh_token", refreshToken);
        try {
            http.post().uri(logoutPath)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Could not end a Keycloak session ({})", e.getClass().getSimpleName());
        }
    }

    private MultiValueMap<String, String> clientForm() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", keycloak.clientId());
        form.add("client_secret", keycloak.clientSecret());
        return form;
    }

    private Jwt check(String token) {
        try {
            return tokens.decode(token);
        } catch (JwtException e) {
            log.error("A token from Keycloak failed its check: {}", e.getClass().getSimpleName());
            throw new KeycloakUnavailableException("token check failed");
        }
    }

    private static RuntimeException refusal(TokenError error) {
        if (error != null && "invalid_grant".equals(error.error())) {
            if (ACCOUNT_DISABLED.equals(error.description())) {
                return new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED",
                        "Ce compte est désactivé. Contactez l'administration.");
            }
            if (error.description() != null && error.description().contains("not fully set up")) {
                return new PasswordChangeRequiredException();
            }
            // A locked account answers exactly like a wrong password: a 423 would tell anyone that the
            // email has an account (Keycloak never locks an unknown email). Decided on MLink-55.
            return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                    "E-mail ou mot de passe incorrect, ou compte temporairement verrouillé.");
        }
        // invalid_client, unauthorized_client...: our configuration is wrong, not the user's input
        log.error("Keycloak refused the API as a client ({})", error == null ? "no body" : error.error());
        return new KeycloakUnavailableException("password grant refused for the client");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("id_token") String idToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("refresh_expires_in") long refreshExpiresIn) {

        @Override
        public String toString() {
            return "TokenResponse[***]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenError(@JsonProperty("error") String error, @JsonProperty("error_description") String description) {}
}
