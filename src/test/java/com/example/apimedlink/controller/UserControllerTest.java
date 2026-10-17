package com.example.apimedlink.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.model.User;
import com.example.apimedlink.service.UserService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import com.example.apimedlink.security.Roles;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class UserControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    UserService users;

    /** A real row to delete; random names so tests never collide. */
    private User newUser() {
        String name = "u" + UUID.randomUUID().toString().substring(0, 8);
        return users.create(UUID.randomUUID(), name, name + "@example.com");
    }

    @Test
    void unauthenticatedIsRejected() throws Exception {
        // /api/users requiert une session (login Keycloak) -> 401 sans session
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousCannotDelete() throws Exception {
        mvc.perform(delete("/api/users/{id}", newUser().getId()).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {Roles.PATIENT, Roles.PRACTITIONER})
    void nonStaffCannotListUsers(String role) throws Exception {
        mvc.perform(get("/api/users").with(user(UUID.randomUUID().toString()).roles(role)))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {Roles.PATIENT, Roles.PRACTITIONER})
    void nonStaffCannotDeleteUsers(String role) throws Exception {
        mvc.perform(delete("/api/users/{id}", newUser().getId())
                        .with(user(UUID.randomUUID().toString()).roles(role))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffCanListUsers() throws Exception {
        mvc.perform(get("/api/users").with(user(UUID.randomUUID().toString()).roles(Roles.ADMINISTRATIVE_STAFF)))
                .andExpect(status().isOk());
    }

    @Test
    void staffCanDeleteUsers() throws Exception {
        mvc.perform(delete("/api/users/{id}", newUser().getId())
                        .with(user(UUID.randomUUID().toString()).roles(Roles.ADMINISTRATIVE_STAFF))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }
}
