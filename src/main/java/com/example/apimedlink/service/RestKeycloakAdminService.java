package com.example.apimedlink.service;

import com.example.apimedlink.config.KeycloakProperties;
import com.example.apimedlink.exception.EmailTakenException;
import com.example.apimedlink.exception.InvalidPasswordException;
import com.example.apimedlink.exception.KeycloakUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.EntityNotFoundException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Keycloak admin REST API as the {@code medlink-admin} service account (client credentials).
 * The token is cached until 30 s before it expires; a 401 drops it and retries the call once.
 * Never logs a password or a token.
 */
@Service
public class RestKeycloakAdminService implements KeycloakAdminService {

    private static final Logger log = LoggerFactory.getLogger(RestKeycloakAdminService.class);
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    private final RestClient http;
    private final KeycloakProperties keycloak;
    private final String users;

    private String token;
    private Instant tokenValidUntil = Instant.MIN;

    public RestKeycloakAdminService(RestClient keycloakRestClient, KeycloakProperties keycloak) {
        this.http = keycloakRestClient;
        this.keycloak = keycloak;
        this.users = "/admin/realms/" + keycloak.realm() + "/users";
    }

    @Override
    public UUID createUser(String username, String email, String firstName, String lastName, String groupPath) {
        UserRepresentation body = new UserRepresentation(
                null, username, email, firstName, lastName, true, false, List.of(groupPath));
        URI location = call("create user", bearer -> http.post().uri(users)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    if (response.getStatusCode().value() == 409) {
                        throw new EmailTakenException();
                    }
                    expectSuccess(response.getStatusCode(), "create user");
                    return response.getHeaders().getLocation();
                }));
        if (location == null) {
            throw new KeycloakUnavailableException("create user: no Location header");
        }
        String path = location.getPath();
        return UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
    }

    @Override
    public void setPassword(UUID id, String password) {
        CredentialRepresentation body = new CredentialRepresentation("password", password, false);
        call("set password", bearer -> http.put().uri(users + "/{id}/reset-password", id)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    if (response.getStatusCode().value() == 400) {
                        ErrorRepresentation error = response.bodyTo(ErrorRepresentation.class);
                        throw new InvalidPasswordException(
                                error == null ? null : error.error(), error == null ? null : error.description());
                    }
                    expectFound(response.getStatusCode(), id);
                    expectSuccess(response.getStatusCode(), "set password");
                    return null;
                }));
    }

    @Override
    public void deleteUser(UUID id) {
        call("delete user", bearer -> http.delete().uri(users + "/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange((request, response) -> {
                    expectFound(response.getStatusCode(), id);
                    expectSuccess(response.getStatusCode(), "delete user");
                    return null;
                }));
    }

    @Override
    public void setEmailVerified(UUID id, boolean verified) {
        UserRepresentation body = new UserRepresentation(null, null, null, null, null, null, verified, null);
        call("set email verified", bearer -> http.put().uri(users + "/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    expectFound(response.getStatusCode(), id);
                    expectSuccess(response.getStatusCode(), "set email verified");
                    return null;
                }));
    }

    @Override
    public KeycloakUser getUser(UUID id) {
        UserRepresentation user = call("get user", bearer -> http.get().uri(users + "/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> {
                    expectFound(response.getStatusCode(), id);
                    expectSuccess(response.getStatusCode(), "get user");
                    return response.bodyTo(UserRepresentation.class);
                }));
        if (user == null) {
            throw new KeycloakUnavailableException("get user: empty body");
        }
        return toUser(id, user);
    }

    @Override
    public Optional<KeycloakUser> findByEmail(String email) {
        UserRepresentation[] found = call("find user by email", bearer -> http.get()
                .uri(users + "?email={email}&exact=true&briefRepresentation=false", email)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(UserRepresentation[].class));
        return found == null || found.length == 0
                ? Optional.empty()
                : Optional.of(toUser(UUID.fromString(found[0].id()), found[0]));
    }

    @Override
    public void requirePasswordChange(UUID id) {
        KeycloakUser user = getUser(id);
        List<String> actions = new ArrayList<>(user.requiredActions());
        if (!actions.contains("UPDATE_PASSWORD")) {
            actions.add("UPDATE_PASSWORD");
        }
        updateRequiredActions(id, actions);
    }

    @Override
    public void endSessions(UUID id) {
        call("end user sessions", bearer -> http.post().uri(users + "/{id}/logout", id)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange((request, response) -> {
                    expectFound(response.getStatusCode(), id);
                    expectSuccess(response.getStatusCode(), "end user sessions");
                    return null;
                }));
    }

    private static KeycloakUser toUser(UUID id, UserRepresentation user) {
        List<String> actions = user.requiredActions() == null ? List.of() : user.requiredActions();
        return new KeycloakUser(id, user.email(), user.firstName(), user.lastName(),
                Boolean.TRUE.equals(user.enabled()), Boolean.TRUE.equals(user.emailVerified()), actions);
    }

    private void updateRequiredActions(UUID id, List<String> actions) {
        RequiredActionsRepresentation body = new RequiredActionsRepresentation(actions);
        call("update required actions", bearer -> http.put().uri(users + "/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    expectFound(response.getStatusCode(), id);
                    expectSuccess(response.getStatusCode(), "update required actions");
                    return null;
                }));
    }

    /** Runs an admin call with the cached token; on a 401 gets a new token and tries once more. */
    private <T> T call(String what, Function<String, T> request) {
        try {
            try {
                return request.apply("Bearer " + token());
            } catch (TokenRefused e) {
                dropToken();
                return request.apply("Bearer " + token());
            }
        } catch (TokenRefused e) {
            throw new KeycloakUnavailableException(what + ": token refused twice");
        } catch (RestClientException e) {
            log.warn("Keycloak {} failed: {}", what, e.getClass().getSimpleName());
            throw new KeycloakUnavailableException(what + ": " + e.getClass().getSimpleName());
        }
    }

    private static void expectFound(HttpStatusCode status, UUID id) {
        if (status.value() == 404) {
            throw new EntityNotFoundException("Keycloak user " + id + " not found");
        }
    }

    private static void expectSuccess(HttpStatusCode status, String what) {
        if (status.value() == 401) {
            throw new TokenRefused();
        }
        if (!status.is2xxSuccessful()) {
            log.warn("Keycloak {} answered {}", what, status.value());
            throw new KeycloakUnavailableException(what + ": HTTP " + status.value());
        }
    }

    private synchronized String token() {
        if (token != null && Instant.now().isBefore(tokenValidUntil)) {
            return token;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", keycloak.admin().clientId());
        form.add("client_secret", keycloak.admin().clientSecret());
        TokenResponse response = http.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", keycloak.realm())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .exchange((request, res) -> {
                    if (!res.getStatusCode().is2xxSuccessful()) {
                        log.warn("Keycloak refused the medlink-admin token: HTTP {}", res.getStatusCode().value());
                        throw new KeycloakUnavailableException("token: HTTP " + res.getStatusCode().value());
                    }
                    return res.bodyTo(TokenResponse.class);
                });
        if (response == null || response.accessToken() == null) {
            throw new KeycloakUnavailableException("token: empty answer");
        }
        token = response.accessToken();
        tokenValidUntil = Instant.now().plusSeconds(response.expiresIn()).minus(EXPIRY_MARGIN);
        return token;
    }

    private synchronized void dropToken() {
        token = null;
        tokenValidUntil = Instant.MIN;
    }

    /** The admin API refused our token (expired or revoked early). */
    private static final class TokenRefused extends RuntimeException {
        TokenRefused() {
            super(null, null, false, false);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserRepresentation(
            String id, String username, String email, String firstName, String lastName,
            Boolean enabled, Boolean emailVerified, List<String> groups, List<String> requiredActions) {

        UserRepresentation(String id, String username, String email, String firstName, String lastName,
                           Boolean enabled, Boolean emailVerified, List<String> groups) {
            this(id, username, email, firstName, lastName, enabled, emailVerified, groups, null);
        }
    }

    record CredentialRepresentation(String type, String value, boolean temporary) {}

    record RequiredActionsRepresentation(List<String> requiredActions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorRepresentation(String error, @JsonProperty("error_description") String description) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken, @JsonProperty("expires_in") long expiresIn) {}
}
