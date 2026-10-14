package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import com.example.apimedlink.config.KeycloakProperties;
import com.example.apimedlink.exception.EmailTakenException;
import com.example.apimedlink.exception.InvalidPasswordException;
import com.example.apimedlink.exception.KeycloakUnavailableException;
import jakarta.persistence.EntityNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

class RestKeycloakAdminServiceTest {

    static final String KC = "http://kc.test";
    static final String TOKEN_URL = KC + "/realms/medlink/protocol/openid-connect/token";
    static final String USERS = KC + "/admin/realms/medlink/users";
    static final UUID ID = UUID.fromString("09aa87e2-f034-4809-84ca-9e71ab5c4b11");

    MockRestServiceServer server;
    RestKeycloakAdminService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(KC);
        server = MockRestServiceServer.bindTo(builder).build();
        KeycloakProperties props = new KeycloakProperties(
                KC, KC, "medlink", "medlink-bff", "bff-secret",
                new KeycloakProperties.Admin("medlink-admin", "admin-secret"));
        service = new RestKeycloakAdminService(builder.build(), props);
    }

    private void expectToken(String token, int expiresIn) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", "medlink-admin");
        form.add("client_secret", "admin-secret");
        server.expect(once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(form))
                .andRespond(withSuccess(
                        "{\"access_token\":\"" + token + "\",\"expires_in\":" + expiresIn + ",\"token_type\":\"Bearer\"}",
                        MediaType.APPLICATION_JSON));
    }

    @Test
    void createUserSendsTheUserAndReadsTheIdFromLocation() {
        expectToken("t1", 300);
        server.expect(requestTo(USERS))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer t1"))
                .andExpect(content().json("""
                        {"username":"amel@example.com","email":"amel@example.com","firstName":"Amel",
                         "lastName":"Ben Salah","enabled":true,"emailVerified":false,"groups":["/patients"]}
                        """, JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatus.CREATED).location(URI.create(USERS + "/" + ID)));

        UUID id = service.createUser("amel@example.com", "amel@example.com", "Amel", "Ben Salah", "/patients");

        assertThat(id).isEqualTo(ID);
        server.verify();
    }

    @Test
    void createUserOnAnExistingEmailIsEmailTaken() {
        expectToken("t1", 300);
        server.expect(requestTo(USERS))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"errorMessage\":\"User exists with same email\"}"));

        assertThatThrownBy(() -> service.createUser("a@b.c", "a@b.c", "A", "B", "/patients"))
                .isInstanceOf(EmailTakenException.class);
    }

    @Test
    void theTokenIsCachedBetweenCalls() {
        expectToken("t1", 300);
        server.expect(requestTo(USERS + "/" + ID)).andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer t1"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo(USERS + "/" + ID)).andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer t1"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        service.deleteUser(ID);
        service.deleteUser(ID);

        server.verify(); // one token request for two calls
    }

    @Test
    void aTokenCloseToExpiryIsRenewed() {
        expectToken("t1", 20); // less than the 30 s margin: not reused
        server.expect(requestTo(USERS + "/" + ID)).andExpect(header("Authorization", "Bearer t1"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        expectToken("t2", 300);
        server.expect(requestTo(USERS + "/" + ID)).andExpect(header("Authorization", "Bearer t2"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        service.deleteUser(ID);
        service.deleteUser(ID);

        server.verify();
    }

    @Test
    void aRefusedTokenIsDroppedAndTheCallRetriedOnce() {
        expectToken("t1", 300);
        server.expect(requestTo(USERS + "/" + ID)).andRespond(withUnauthorizedRequest());
        expectToken("t2", 300);
        server.expect(requestTo(USERS + "/" + ID)).andExpect(header("Authorization", "Bearer t2"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        service.deleteUser(ID);

        server.verify();
    }

    @Test
    void setPasswordIsPermanent() {
        expectToken("t1", 300);
        server.expect(requestTo(USERS + "/" + ID + "/reset-password"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"type\":\"password\",\"value\":\"Valid-Passw0rd-2026\",\"temporary\":false}"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        service.setPassword(ID, "Valid-Passw0rd-2026");

        server.verify();
    }

    @Test
    void aPolicyRefusalKeepsKeycloaksKeyAndSaysTheRuleInFrench() {
        expectToken("t1", 300);
        server.expect(requestTo(USERS + "/" + ID + "/reset-password"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
                        {"error":"invalidPasswordMinLengthMessage","error_description":"Invalid password: minimum length 12."}
                        """));

        assertThatThrownBy(() -> service.setPassword(ID, "short"))
                .isInstanceOfSatisfying(InvalidPasswordException.class, e -> {
                    assertThat(e.getErrorKey()).isEqualTo("invalidPasswordMinLengthMessage");
                    assertThat(e.getMessage()).isEqualTo("Le mot de passe doit contenir au moins 12 caractères.");
                    assertThat(e.getDetails()).containsEntry("password", e.getMessage());
                    assertThat(e.getMessage()).doesNotContain("short");
                });
    }

    @Test
    void setPasswordOnAnUnknownUserIsNotFound() {
        expectToken("t1", 300);
        server.expect(requestTo(USERS + "/" + ID + "/reset-password")).andRespond(withResourceNotFound());

        assertThatThrownBy(() -> service.setPassword(ID, "Valid-Passw0rd-2026"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void deleteUnknownUserIsNotFound() {
        expectToken("t1", 300);
        server.expect(requestTo(USERS + "/" + ID)).andExpect(method(HttpMethod.DELETE)).andRespond(withResourceNotFound());

        assertThatThrownBy(() -> service.deleteUser(ID)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void setEmailVerifiedSendsOnlyThatField() {
        expectToken("t1", 300);
        server.expect(requestTo(USERS + "/" + ID))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"emailVerified\":true}", JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        service.setEmailVerified(ID, true);

        server.verify();
    }

    @Test
    void setEmailVerifiedOnUnknownUserIsNotFound() {
        expectToken("t1", 300);
        server.expect(requestTo(USERS + "/" + ID)).andRespond(withResourceNotFound());

        assertThatThrownBy(() -> service.setEmailVerified(ID, true)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void getUserReadsTheFieldsWeUse() {
        expectToken("t1", 300);
        server.expect(requestTo(USERS + "/" + ID)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"%s","username":"amel@example.com","firstName":"Amel","lastName":"Ben Salah",
                         "email":"amel@example.com","emailVerified":false,"enabled":true,"totp":false,
                         "requiredActions":[],"access":{"manage":true}}
                        """.formatted(ID), MediaType.APPLICATION_JSON));

        KeycloakUser user = service.getUser(ID);

        assertThat(user).isEqualTo(new KeycloakUser(ID, "amel@example.com", "Amel", "Ben Salah", true, false));
    }

    @Test
    void getUnknownUserIsNotFound() {
        expectToken("t1", 300);
        server.expect(requestTo(USERS + "/" + ID)).andRespond(withResourceNotFound());

        assertThatThrownBy(() -> service.getUser(ID)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void aServerErrorIsIdentityUnavailable() {
        expectToken("t1", 300);
        server.expect(requestTo(USERS + "/" + ID)).andRespond(withServerError());

        assertThatThrownBy(() -> service.getUser(ID)).isInstanceOf(KeycloakUnavailableException.class);
    }

    @Test
    void noAnswerIsIdentityUnavailable() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withException(new IOException("Connection refused")));

        assertThatThrownBy(() -> service.getUser(ID)).isInstanceOf(KeycloakUnavailableException.class);
    }

    @Test
    void aRefusedServiceAccountIsIdentityUnavailable() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withUnauthorizedRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"unauthorized_client\"}"));

        assertThatThrownBy(() -> service.getUser(ID)).isInstanceOf(KeycloakUnavailableException.class);
    }
}
