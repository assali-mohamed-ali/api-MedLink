package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.service.AdminUserService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * MLink-70: a disabled account is out at once and cannot log in again; enabled again, it can. Real Keycloak,
 * real API, real database.
 */
class AdminAccountStatusFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Status-Change-2026!";

    final KeycloakConsole console = new KeycloakConsole();

    @Autowired
    AdminUserService admin;

    @Test
    void aDisabledAccountIsOutAtOnceAndCannotLogInUntilEnabledAgain() throws Exception {
        String email = uniqueEmail("status");
        UUID id = console.createUser(email, PASSWORD);
        Browser patient = new Browser().login(email, PASSWORD);
        assertThat(patient.call("/api/users/_health").statusCode()).as("logged in").isEqualTo(200);

        admin.setEnabled(id, false, UUID.randomUUID());

        assertThat(patient.call("/api/users/_health").statusCode()).as("the open session, at once").isEqualTo(401);
        assertThat(console.sessions(id)).as("Keycloak sessions").isEmpty();
        assertThat(new Browser().apiLogin(email, PASSWORD).statusCode()).as("login while disabled").isEqualTo(403);

        admin.setEnabled(id, true, UUID.randomUUID());

        assertThat(new Browser().login(email, PASSWORD).call("/api/users/_health").statusCode())
                .as("login after enabling again").isEqualTo(200);
    }
}
