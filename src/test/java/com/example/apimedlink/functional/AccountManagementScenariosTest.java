package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.client.ExpectedCount.between;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withCreatedEntity;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.config.KeycloakProperties;
import com.example.apimedlink.model.Clinic;
import com.example.apimedlink.model.Specialty;
import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.ClinicRepository;
import com.example.apimedlink.repository.SpecialtyRepository;
import com.example.apimedlink.repository.UserRepository;
import com.example.apimedlink.security.Roles;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.client.RestClient;

/**
 * MLink-74 (task 20.6): the account-management story, end to end. The real controller, service and database
 * (Testcontainers); Keycloak is answered at the HTTP level by a fake server, and KeycloakAdminService itself is
 * not mocked. One test per scenario of the story.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AccountManagementScenariosTest.FakeKeycloak.class})
class AccountManagementScenariosTest {

    static final String KC = "http://kc.test";

    /** Replaces the API's Keycloak client by one bound to a fake server. */
    @TestConfiguration
    static class FakeKeycloak {

        static final RestClient.Builder BUILDER = RestClient.builder().baseUrl(KC);
        static final MockRestServiceServer SERVER =
                MockRestServiceServer.bindTo(BUILDER).ignoreExpectOrder(true).build();

        @Bean
        @Primary
        RestClient fakeKeycloakRestClient() {
            return BUILDER.build();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate db;

    @Autowired
    UserRepository users;

    @Autowired
    SpecialtyRepository specialties;

    @Autowired
    ClinicRepository clinics;

    @Autowired
    KeycloakProperties keycloak;

    @MockitoBean
    JavaMailSender mailSender;

    UUID staffId;

    @BeforeEach
    void setUp() {
        staffId = UUID.randomUUID();
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage((Session) null));
        FakeKeycloak.SERVER.reset();
        // The admin token is cached by the service: it is asked for zero or more times
        FakeKeycloak.SERVER.expect(between(0, 100), req -> assertThat(req.getURI().getPath()).isEqualTo(tokenPath()))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"fake-token\",\"expires_in\":300,\"token_type\":\"Bearer\"}",
                        MediaType.APPLICATION_JSON));
    }

    @AfterEach
    void everyExpectedKeycloakCallWasMade() {
        FakeKeycloak.SERVER.verify();
    }

        // Scenario 1: staff creates a practitioner -> the account appears in the list -> one audit row
    @Test
    void staffCreatesAPractitionerThenSeesItInTheListWithOneAuditRow() throws Exception {
        UUID newId = UUID.randomUUID();
        String email = "scenario-" + newId + "@example.com";
        UUID specialtyId = specialties.save(new Specialty("Spec-" + newId)).getId();
        UUID clinicId = clinics.save(new Clinic("Clinic-" + newId, "Sousse")).getId();
        // All the expectations are declared before the first call: the server refuses new ones afterwards
        expectKeycloak(HttpMethod.POST, usersPath())
                .andRespond(withCreatedEntity(URI.create(KC + usersPath() + "/" + newId)));
        expectKeycloak(HttpMethod.GET, usersPath() + "/count").andRespond(withSuccess("1", MediaType.APPLICATION_JSON));
        expectKeycloak(HttpMethod.GET, usersPath()).andRespond(withSuccess(
                "[" + userJson(newId, email) + "]", MediaType.APPLICATION_JSON));
        expectKeycloak(HttpMethod.GET, usersPath() + "/" + newId + "/groups").andRespond(withSuccess(
                "[{\"id\":\"group-practitioners\",\"path\":\"/practitioners\"}]", MediaType.APPLICATION_JSON));

        asStaff(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","firstName":"Karim","lastName":"Mansour","role":"PRACTITIONER",
                 "specialtyId":"%s","clinicIds":["%s"]}
                """.formatted(email, specialtyId, clinicId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(newId.toString()))
                .andExpect(jsonPath("$.role").value("PRACTITIONER"));

        asStaff(get("/api/admin/users").param("search", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(newId.toString()))
                .andExpect(jsonPath("$.items[0].email").value(email))
                .andExpect(jsonPath("$.items[0].role").value("PRACTITIONER"));

        List<Map<String, Object>> rows = db.queryForList(
                "select action, outcome, user_id from audit_entries where resource_id = ?", newId.toString());
        assertThat(rows).as("exactly one audit row for the new account").hasSize(1);
        assertThat(rows.get(0)).containsEntry("action", "ACCOUNT_CREATED")
                .containsEntry("outcome", "SUCCESS")
                .containsEntry("user_id", staffId);
    }

    // Scenario 2: same email again -> 409 -> nothing created -> a FAILURE audit row
    @Test
    void theSameEmailAgainIsAConflictCreatesNothingAndIsAuditedAsAFailure() throws Exception {
        UUID newId = UUID.randomUUID();
        String email = "scenario-" + newId + "@example.com";
        String body = """
                {"email":"%s","firstName":"Salma","lastName":"Trabelsi","role":"ADMINISTRATIVE_STAFF"}
                """.formatted(email);
        // One creation call is expected in Keycloak, not two: the second request never reaches it
        expectKeycloak(HttpMethod.POST, usersPath())
                .andRespond(withCreatedEntity(URI.create(KC + usersPath() + "/" + newId)));
        asStaff(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        int failuresBefore = failedCreations();

        asStaff(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));

        assertThat(db.queryForObject("select count(*) from users where email = ?", Integer.class, email))
                .as("nothing more was created").isEqualTo(1);
        assertThat(failedCreations()).as("one FAILURE audit row").isEqualTo(failuresBefore + 1);
    }

    // Scenario 3: staff disables the account -> Keycloak receives "disable" then "end sessions"
    @Test
    void disablingAnAccountTellsKeycloakToDisableThenToEndTheSessions() throws Exception {
        UUID targetId = UUID.randomUUID();
        String email = "scenario-" + targetId + "@example.com";
        users.save(User.builder().id(targetId).username(email).email(email).build());
        List<String> received = new ArrayList<>();
        expectKeycloak(HttpMethod.PUT, usersPath() + "/" + targetId)
                .andExpect(content().json("{\"enabled\":false}", JsonCompareMode.LENIENT))
                .andExpect(req -> received.add("DISABLE"))
                .andRespond(withNoContent());
        expectKeycloak(HttpMethod.POST, usersPath() + "/" + targetId + "/logout")
                .andExpect(req -> received.add("END_SESSIONS"))
                .andRespond(withNoContent());

        asStaff(patch("/api/admin/users/" + targetId + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
                .andExpect(status().isNoContent());

        assertThat(received).as("Keycloak calls, in order").containsExactly("DISABLE", "END_SESSIONS");
        assertThat(db.queryForObject(
                "select count(*) from audit_entries where resource_id = ? and action = 'ACCOUNT_DISABLED' "
                        + "and outcome = 'SUCCESS'", Integer.class, targetId.toString())).isEqualTo(1);
    }

    // Scenario 4: a patient or a practitioner calls any admin route -> 403 -> an ACCESS_DENIED audit row
    @Test
    void aPatientOrAPractitionerIsRefusedOnEveryAdminRouteAndAudited() throws Exception {
        UUID someone = UUID.randomUUID();
        List<MockHttpServletRequestBuilder> routes = List.of(
                MockMvcRequestBuilders.get("/api/admin/users"),
                MockMvcRequestBuilders.post("/api/admin/users"),
                MockMvcRequestBuilders.put("/api/admin/users/" + someone + "/role"),
                MockMvcRequestBuilders.patch("/api/admin/users/" + someone + "/status"),
                MockMvcRequestBuilders.post("/api/admin/users/" + someone + "/force-password-reset"));

        for (String role : List.of(Roles.PATIENT, Roles.PRACTITIONER)) {
            UUID caller = UUID.randomUUID();
            for (MockHttpServletRequestBuilder route : routes) {
                mvc.perform(route.with(user(caller.toString()).roles(role)).with(csrf())
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isForbidden());
            }

            List<Map<String, Object>> rows = db.queryForList(
                    "select action, outcome from audit_entries where user_id = ?", caller);
            assertThat(rows).as("one ACCESS_DENIED row per refused call (" + role + ")").hasSize(routes.size());
            assertThat(rows).allSatisfy(row -> assertThat(row)
                    .containsEntry("action", "ACCESS_DENIED").containsEntry("outcome", "DENIED"));
        }
    }

    private ResultActions asStaff(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(user(staffId.toString()).roles(Roles.ADMINISTRATIVE_STAFF)).with(csrf()));
    }

    /** A call the fake Keycloak must receive exactly once, matched on its method and its path. */
    private static ResponseActions expectKeycloak(HttpMethod verb, String path) {
        return FakeKeycloak.SERVER.expect(once(), req -> assertThat(req.getURI().getPath()).isEqualTo(path))
                .andExpect(method(verb));
    }

    private String usersPath() {
        return "/admin/realms/" + keycloak.realm() + "/users";
    }

    private String tokenPath() {
        return "/realms/" + keycloak.realm() + "/protocol/openid-connect/token";
    }

    private static String userJson(UUID id, String email) {
        return """
                {"id":"%s","username":"%s","email":"%s","firstName":"Karim","lastName":"Mansour",
                 "enabled":true,"emailVerified":false}
                """.formatted(id, email, email);
    }

    private int failedCreations() {
        Integer count = db.queryForObject("select count(*) from audit_entries where resource_id = 'NEW_ACCOUNT' "
                + "and action = 'ACCOUNT_CREATED' and outcome = 'FAILURE'", Integer.class);
        return count == null ? 0 : count;
    }
}