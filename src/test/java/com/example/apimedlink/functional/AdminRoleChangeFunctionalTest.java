package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.repository.SessionRevocationStore;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.AdminUserService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * MLink-69 (review of PR #112): a role change ends the user's open sessions at once. A session keeps the roles
 * it was opened with, so a demoted admin must not stay admin. Real Keycloak, real API, real database.
 */
class AdminRoleChangeFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Role-Change-2026!";
    /** A staff-only route; size=1 keeps the Keycloak calls few. */
    static final String STAFF_ONLY = "/api/admin/users?size=1";

    final KeycloakConsole console = new KeycloakConsole();

    @Autowired
    AdminUserService admin;

    @Autowired
    SessionRevocationStore revocations;

    @Test
    void aDemotedAdminLosesAdminAccessAtOnceAndLogsInAgainWithTheNewRoleOnly() throws Exception {
        String email = uniqueEmail("demoted");
        UUID id = console.createUser(email, PASSWORD, "/administrative-staff");
        Browser demoted = new Browser().login(email, PASSWORD);
        assertThat(demoted.call(STAFF_ONLY).statusCode()).as("admin before the change").isEqualTo(200);

        admin.changeRole(id, Roles.PRACTITIONER);

        assertThat(demoted.call(STAFF_ONLY).statusCode()).as("the old session, at once").isEqualTo(401);
        assertThat(console.groups(id)).containsExactly("/practitioners");
        assertThat(console.sessions(id)).as("Keycloak sessions").isEmpty();
        assertThat(revocations.revokedAt(id)).as("the API's own mark").isPresent();

        Browser again = new Browser().login(email, PASSWORD);
        assertThat(again.sessionRoles()).as("roles of the new session").containsExactly(Roles.PRACTITIONER);
        assertThat(again.call(STAFF_ONLY).statusCode()).as("no longer admin").isEqualTo(403);
    }
}
