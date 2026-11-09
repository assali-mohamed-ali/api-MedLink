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
    static final String GROUP_BY_PATH = KC + "/admin/realms/medlink/group-by-path/";
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

    // ---- searchUsers (MLink-69) ---------------------------------------------------------------------

    @Test
    void searchUsersReadsTheTotalThePageAndEachUsersGroup() {
        expectToken("t1", 300);
        server.expect(requestTo(USERS + "/count?search=kar"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer t1"))
                .andRespond(withSuccess("3", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS + "?search=kar&first=20&max=10&briefRepresentation=false"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"id":"%s","username":"karim@example.com","email":"karim@example.com",
                          "firstName":"Karim","lastName":"Mansour","enabled":true,"emailVerified":true}]
                        """.formatted(ID), MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS + "/" + ID + "/groups"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":\"g-pr\",\"path\":\"/practitioners\"}]", MediaType.APPLICATION_JSON));

        KeycloakUserPage page = service.searchUsers("kar", 2, 10);

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).user())
                .isEqualTo(new KeycloakUser(ID, "karim@example.com", "Karim", "Mansour", true, true));
        assertThat(page.items().get(0).groupPath()).isEqualTo("/practitioners");
        server.verify();
    }

    @Test
    void searchWithoutTextSendsNoSearchFilter() {
        expectToken("t1", 300);
        server.expect(requestTo(USERS + "/count")).andRespond(withSuccess("0", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS + "?first=0&max=20&briefRepresentation=false"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        KeycloakUserPage page = service.searchUsers("   ", 0, 20);

        assertThat(page.total()).isZero();
        assertThat(page.items()).isEmpty();
        server.verify();
    }

    @Test
    void searchTextCannotAddAParameterToTheKeycloakUrl() {
        expectToken("t1", 300);
        // "&" and "=" are dropped, so the text stays one search value
        server.expect(requestTo(USERS + "/count?search=karmax1000"))
                .andRespond(withSuccess("0", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS + "?search=karmax1000&first=0&max=20&briefRepresentation=false"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        service.searchUsers("kar&max=1000", 0, 20);

        server.verify();
    }

    @Test
    void aUserInNoGroupHasNoGroupPath() {
        expectToken("t1", 300);
        server.expect(requestTo(USERS + "/count")).andRespond(withSuccess("1", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS + "?first=0&max=20&briefRepresentation=false"))
                .andRespond(withSuccess("""
                        [{"id":"%s","email":"a@b.c","firstName":"A","lastName":"B","enabled":true,"emailVerified":false}]
                        """.formatted(ID), MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS + "/" + ID + "/groups"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        KeycloakUserPage page = service.searchUsers("", 0, 20);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).groupPath()).isNull();
    }

    @Test
    void aSearchServerErrorIsIdentityUnavailable() {
        expectToken("t1", 300);
        server.expect(requestTo(USERS + "/count")).andRespond(withServerError());

        assertThatThrownBy(() -> service.searchUsers("", 0, 20)).isInstanceOf(KeycloakUnavailableException.class);
    }

    // ---- moveToGroup (MLink-69) ---------------------------------------------------------------------

    private void expectGroup(String path, String id) {
        server.expect(requestTo(GROUP_BY_PATH + path))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"" + id + "\",\"path\":\"/" + path + "\"}", MediaType.APPLICATION_JSON));
    }

    private void expectGroupsOfUser(String json) {
        server.expect(requestTo(USERS + "/" + ID + "/groups"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    void moveToGroupAddsTheNewGroupThenRemovesTheOldOne() {
        expectToken("t1", 300);
        expectGroup("practitioners", "g-pr");
        expectGroupsOfUser("[{\"id\":\"g-staff\",\"path\":\"/administrative-staff\"}]");
        // The server expects these in order: the new group is added first, so a failure never leaves no role
        server.expect(requestTo(USERS + "/" + ID + "/groups/g-pr"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo(USERS + "/" + ID + "/groups/g-staff"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        service.moveToGroup(ID, "/practitioners");

        server.verify();
    }

    @Test
    void moveToGroupDoesNothingWhenTheUserIsAlreadyInTheGroup() {
        expectToken("t1", 300);
        expectGroup("practitioners", "g-pr");
        expectGroupsOfUser("[{\"id\":\"g-pr\",\"path\":\"/practitioners\"}]");

        service.moveToGroup(ID, "/practitioners"); // no PUT, no DELETE: any other request fails the test

        server.verify();
    }

    @Test
    void moveToGroupOfAnUnknownUserIsNotFound() {
        expectToken("t1", 300);
        expectGroup("practitioners", "g-pr");
        server.expect(requestTo(USERS + "/" + ID + "/groups")).andRespond(withResourceNotFound());

        assertThatThrownBy(() -> service.moveToGroup(ID, "/practitioners"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void moveToAnUnknownGroupIsIdentityUnavailable() {
        expectToken("t1", 300);
        server.expect(requestTo(GROUP_BY_PATH + "nurses")).andRespond(withResourceNotFound());

        assertThatThrownBy(() -> service.moveToGroup(ID, "/nurses"))
                .isInstanceOf(KeycloakUnavailableException.class);
    }

    @Test
    void aFailureAddingTheNewGroupLeavesTheOldGroupUntouched() {
        expectToken("t1", 300);
        expectGroup("practitioners", "g-pr");
        expectGroupsOfUser("[{\"id\":\"g-staff\",\"path\":\"/administrative-staff\"}]");
        server.expect(requestTo(USERS + "/" + ID + "/groups/g-pr"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withServerError());

        assertThatThrownBy(() -> service.moveToGroup(ID, "/practitioners"))
                .isInstanceOf(KeycloakUnavailableException.class);

        server.verify(); // no DELETE was sent: the user keeps their old role
    }

    @Test
    void aFailureRemovingTheOldGroupKeepsBothGroupsSoLoginRefusesTheAccount() {
        expectToken("t1", 300);
        expectGroup("practitioners", "g-pr");
        expectGroupsOfUser("[{\"id\":\"g-staff\",\"path\":\"/administrative-staff\"}]");
        server.expect(requestTo(USERS + "/" + ID + "/groups/g-pr"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo(USERS + "/" + ID + "/groups/g-staff"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withServerError());

        assertThatThrownBy(() -> service.moveToGroup(ID, "/practitioners"))
                .isInstanceOf(KeycloakUnavailableException.class);

        server.verify(); // two roles at worst (login refuses it), never none; staff send the change again
    }

    @Test
    void aUserInSeveralGroupsKeepsEveryGroupPath() {
        expectToken("t1", 300);
        server.expect(requestTo(USERS + "/count")).andRespond(withSuccess("1", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS + "?first=0&max=20&briefRepresentation=false"))
                .andRespond(withSuccess("""
                        [{"id":"%s","email":"a@b.c","firstName":"A","lastName":"B","enabled":true,"emailVerified":true}]
                        """.formatted(ID), MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS + "/" + ID + "/groups"))
                .andRespond(withSuccess("""
                        [{"id":"g-pr","path":"/practitioners"},{"id":"g-staff","path":"/administrative-staff"}]
                        """, MediaType.APPLICATION_JSON));

        KeycloakUserPage page = service.searchUsers("", 0, 20);

        assertThat(page.items().get(0).groupPaths()).containsExactly("/practitioners", "/administrative-staff");
        assertThat(page.items().get(0).groupPath()).as("no single group").isNull();
    }

    
    @Test
    void setEnabledPutsTheEnabledFlagOnTheUser() {
        expectToken("t", 300);
        server.expect(once(), requestTo(USERS + "/" + ID))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("Authorization", "Bearer t"))
                .andExpect(content().json("{\"enabled\":false}", JsonCompareMode.LENIENT))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        service.setEnabled(ID, false);

        server.verify();
    }

    @Test
    void setEnabledOfAnUnknownUserIsNotFound() {
        expectToken("t", 300);
        server.expect(once(), requestTo(USERS + "/" + ID))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> service.setEnabled(ID, true))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void setEnabledWhenKeycloakFailsIsUnavailable() {
        expectToken("t", 300);
        server.expect(once(), requestTo(USERS + "/" + ID))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withServerError());

        assertThatThrownBy(() -> service.setEnabled(ID, true))
                .isInstanceOf(KeycloakUnavailableException.class);
    }
}
