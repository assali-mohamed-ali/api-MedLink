package com.example.apimedlink.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.apimedlink.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MLink-59, review on PR #56: a refusal is recorded even when the caller's identity cannot be read as
 * a Keycloak id. A real login always gives a UUID, so only a fake principal reaches this branch:
 * MockMvc, not a functional test.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class AuditIdentityTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate db;

    @Test
    void aRefusalIsRecordedEvenWhenTheCallerIdIsNotAUuid() throws Exception {
        int before = rowsWithoutUser("GET /api/users");

        mvc.perform(get("/api/users").with(user("not-a-uuid").roles(Roles.PATIENT)))
                .andExpect(status().isForbidden());

        assertThat(rowsWithoutUser("GET /api/users")).as("recorded, with no user id").isEqualTo(before + 1);
    }

    private int rowsWithoutUser(String resource) {
        return db.queryForObject(
                "select count(*) from audit_entries where user_id is null and resource_id = ?", Integer.class, resource);
    }
}
