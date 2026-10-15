package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.apimedlink.config.KeycloakProperties;
import com.example.apimedlink.exception.KeycloakUnavailableException;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * MLink-55, what a real Keycloak cannot do on demand: not answer, fail, refuse the API itself.
 * Keycloak is played by MockRestServiceServer. The real behaviour is in LoginFunctionalTest.
 */
class KeycloakLoginClientTest {

    static final String KC = "http://kc.test";
    static final String TOKEN_URL = KC + "/realms/medlink/protocol/openid-connect/token";
    static final String LOGOUT_URL = KC + "/realms/medlink/protocol/openid-connect/logout";

    MockRestServiceServer keycloak;
    KeycloakLoginClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(KC);
        keycloak = MockRestServiceServer.bindTo(builder).build();
        KeycloakProperties props = new KeycloakProperties(
                KC, KC, "medlink", "medlink-bff", "bff-secret",
                new KeycloakProperties.Admin("medlink-admin", "admin-secret"));
        client = new KeycloakLoginClient(builder.build(), props);
    }

    @Test
    void noAnswerIsIdentityUnavailable() {
        keycloak.expect(requestTo(TOKEN_URL)).andRespond(withException(new IOException("Connection refused")));

        assertThatThrownBy(() -> client.passwordGrant("a@example.com", "Some-Pass-2026!"))
                .isInstanceOf(KeycloakUnavailableException.class)
                .hasFieldOrPropertyWithValue("code", "IDENTITY_UNAVAILABLE")
                .hasFieldOrPropertyWithValue("status", HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void aServerErrorIsIdentityUnavailable() {
        keycloak.expect(requestTo(TOKEN_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.passwordGrant("a@example.com", "Some-Pass-2026!"))
                .isInstanceOf(KeycloakUnavailableException.class);
    }

    @Test
    void aRefusedClientIsOurFaultNotTheUsers() {
        keycloak.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"invalid_client\",\"error_description\":\"Invalid client credentials\"}"));

        assertThatThrownBy(() -> client.passwordGrant("a@example.com", "Some-Pass-2026!"))
                .as("a wrong client secret must not look like a wrong password")
                .isInstanceOf(KeycloakUnavailableException.class);
    }

    @Test
    void anIncompleteTokenAnswerIsIdentityUnavailable() {
        keycloak.expect(requestTo(TOKEN_URL)).andRespond(
                withSuccess("{\"access_token\":\"x\",\"refresh_token\":\"y\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.passwordGrant("a@example.com", "Some-Pass-2026!"))
                .isInstanceOf(KeycloakUnavailableException.class);
    }

    @Test
    void aFailedLogoutIsOnlyLogged() {
        keycloak.expect(requestTo(LOGOUT_URL)).andExpect(method(HttpMethod.POST)).andRespond(withServerError());

        assertThatCode(() -> client.endSession("some-refresh-token")).doesNotThrowAnyException();
        keycloak.verify();
    }
}