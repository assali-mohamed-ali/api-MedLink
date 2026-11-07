package com.example.apimedlink.controller;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.exception.EmailTakenException;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.KeycloakAdminService;
import com.example.apimedlink.service.KeycloakUser;
import com.example.apimedlink.service.KeycloakUserPage;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Endpoints 12, 13, 15 and 16 (MLink-69, MLink-70), written from the contract. Real database; Keycloak is a mock. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminUserControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    KeycloakAdminService keycloak;

    UUID keycloakId;
    String email;

    @BeforeEach
    void setUp() {
        keycloakId = UUID.randomUUID();
        email = "karim-" + keycloakId + "@example.com";
        when(keycloak.createUser(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(keycloakId);
    }

    private ResultActions asStaff(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(user("staff").roles(Roles.ADMINISTRATIVE_STAFF)).with(csrf()));
    }

    /** Staff whose name is their Keycloak id, as in a real session (CurrentUser reads it as a UUID). */
    private ResultActions asStaffWithId(UUID staffId, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(user(staffId.toString()).roles(Roles.ADMINISTRATIVE_STAFF)).with(csrf()));
    }

    private static String createBody(String email, String role) {
        return """
                {"email":"%s","firstName":"Karim","lastName":"Mansour","role":"%s"}
                """.formatted(email, role);
    }

    @Test
    void listReturnsItemsAndTotal() throws Exception {
        KeycloakUser karim = new KeycloakUser(keycloakId, email, "Karim", "Mansour", true, true);
        when(keycloak.searchUsers("kar", 0, 20))
                .thenReturn(new KeycloakUserPage(List.of(new KeycloakUserPage.Entry(karim, "/practitioners")), 1));

        asStaff(get("/api/admin/users").param("search", "kar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(keycloakId.toString()))
                .andExpect(jsonPath("$.items[0].role").value("PRACTITIONER"))
                .andExpect(jsonPath("$.items[0].enabled").value(true))
                .andExpect(jsonPath("$.items[0].emailVerified").value(true));
    }

    @Test
    void aPageSizeOutOfRangeIsRefused() throws Exception {
        asStaff(get("/api/admin/users").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.size").isNotEmpty());
    }

    @Test
    void aValidBodyCreatesAStaffAccount() throws Exception {
        asStaff(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                .content(createBody(email, Roles.PRACTITIONER)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(keycloakId.toString()))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("PRACTITIONER"))
                .andExpect(jsonPath("$.emailVerified").value(false));

        verify(keycloak).createUser(email, email, "Karim", "Mansour", "/practitioners");
    }

    @Test
    void aPatientCannotBeCreatedByStaff() throws Exception {
        asStaff(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                .content(createBody(email, Roles.PATIENT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.role").isNotEmpty());
    }

    @Test
    void anInvalidEmailIsRefused() throws Exception {
        asStaff(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                .content(createBody("not-an-email", Roles.PRACTITIONER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.email").isNotEmpty());
    }

    @Test
    void anEmailAlreadyTakenIsAConflict() throws Exception {
        when(keycloak.createUser(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new EmailTakenException());

        asStaff(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                .content(createBody(email, Roles.ADMINISTRATIVE_STAFF)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void aRoleChangeMovesTheUserToTheNewGroup() throws Exception {
        asStaff(put("/api/admin/users/" + keycloakId + "/role").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMINISTRATIVE_STAFF\"}"))
                .andExpect(status().isNoContent());

        verify(keycloak).moveToGroup(keycloakId, "/administrative-staff");
        verify(keycloak).endSessions(keycloakId);
    }

    @Test
    void aRoleChangeOfAnUnknownUserIsNotFound() throws Exception {
        doThrow(new EntityNotFoundException("unknown")).when(keycloak).moveToGroup(eq(keycloakId), anyString());

        asStaff(put("/api/admin/users/" + keycloakId + "/role").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"PRACTITIONER\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRoleChangeToPatientIsRefused() throws Exception {
        asStaff(put("/api/admin/users/" + keycloakId + "/role").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"PATIENT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.role").isNotEmpty());
    }

    @Test
    void disablingAnAccountIsNoContentAndEndsItsSessions() throws Exception {
        asStaffWithId(UUID.randomUUID(), patch("/api/admin/users/" + keycloakId + "/status")
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isNoContent());

        verify(keycloak).setEnabled(keycloakId, false);
        verify(keycloak).endSessions(keycloakId);
    }

    @Test
    void enablingAnAccountIsNoContent() throws Exception {
        asStaffWithId(UUID.randomUUID(), patch("/api/admin/users/" + keycloakId + "/status")
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isNoContent());

        verify(keycloak).setEnabled(keycloakId, true);
    }

    @Test
    void staffDisablingThemselvesIsAConflict() throws Exception {
        asStaffWithId(keycloakId, patch("/api/admin/users/" + keycloakId + "/status")
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_DISABLE_SELF"));
    }

    @Test
    void aStatusChangeOfAnUnknownUserIsNotFound() throws Exception {
        doThrow(new EntityNotFoundException("unknown")).when(keycloak).setEnabled(eq(keycloakId), anyBoolean());

        asStaffWithId(UUID.randomUUID(), patch("/api/admin/users/" + keycloakId + "/status")
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aStatusChangeWithoutAnEnabledFlagIsRefused() throws Exception {
        asStaffWithId(UUID.randomUUID(), patch("/api/admin/users/" + keycloakId + "/status")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.enabled").isNotEmpty());
    }

    @Test
    void aPatientIsRefusedEvenWithAValidBody() throws Exception {
        mvc.perform(post("/api/admin/users").with(user("patient").roles(Roles.PATIENT)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(email, Roles.PRACTITIONER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aPractitionerIsRefusedEvenWithAValidBody() throws Exception {
        mvc.perform(post("/api/admin/users").with(user("practitioner").roles(Roles.PRACTITIONER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(email, Roles.PRACTITIONER)))
                .andExpect(status().isForbidden());
    }
}
