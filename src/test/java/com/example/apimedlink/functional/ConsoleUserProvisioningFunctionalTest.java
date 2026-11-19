package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.UserRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * GitHub issue #29: a user created in the Keycloak console (not through our signup) gets exactly
 * one row in our database, at their first login, under their Keycloak id.
 */
class ConsoleUserProvisioningFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Console-Pass-2026!";

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate db;

    final KeycloakConsole console = new KeycloakConsole();

    @Test
    void aConsoleUserGetsExactlyOneRowAtFirstLogin() throws Exception {
        String email = uniqueEmail("console");
        UUID id = console.createUser(email, PASSWORD);
        assertThat(users.findById(id)).as("no row before the first login").isEmpty();

        Browser browser = new Browser().login(email, PASSWORD);

        assertThat(browser.call("/api/users/_health").statusCode()).isEqualTo(200);
        User row = users.findById(id).orElseThrow();
        assertThat(row.getEmail()).isEqualTo(email);
        assertThat(row.getUsername()).isEqualTo(email);

        new Browser().login(email, PASSWORD); // a second login, from another browser

        assertThat(users.findAll().stream().filter(u -> u.getEmail().equals(email))).hasSize(1);
    }

    @Test
    void anEmailChangedInTheConsoleIsUpdatedAtTheNextLogin() throws Exception {
        String email = uniqueEmail("before");
        UUID id = console.createUser(email, PASSWORD);
        new Browser().login(email, PASSWORD);

        String changed = uniqueEmail("after");
        int auditRowsBefore = accountUpdateAuditRows(id);
        console.update(id, Map.of("email", changed, "emailVerified", true));
        Browser browser = new Browser().login(email, PASSWORD); // the username stays the old email

        assertThat(browser.call("/api/users/_health").statusCode()).isEqualTo(200);
        assertThat(users.findById(id).orElseThrow().getEmail()).isEqualTo(changed);
        assertThat(accountUpdateAuditRows(id)).isEqualTo(auditRowsBefore + 1);
        assertThat(db.queryForObject(
                "select outcome from audit_entries where action = 'ACCOUNT_UPDATED' "
                        + "and resource_type = 'USER' and resource_id = ?",
                String.class, id.toString())).isEqualTo("SUCCESS");
    }

    @Test
    void anEmailThatBelongsToAnotherAccountIsRefusedAndNothingIsOverwritten() throws Exception {
        String email = uniqueEmail("taken");
        UUID ours = UUID.randomUUID();
        users.save(User.builder().id(ours).username("someone-else-" + ours).email(email).build());
        UUID recreated = console.createUser(email, PASSWORD); // same email, new Keycloak id

        Browser browser = new Browser().login(email, PASSWORD);

        assertThat(browser.call("/api/users/_health").statusCode()).as("no session").isEqualTo(401);
        assertThat(users.findById(recreated)).isEmpty();
        assertThat(users.findById(ours).orElseThrow().getEmail()).isEqualTo(email);
    }

    private int accountUpdateAuditRows(UUID id) {
        return db.queryForObject(
                "select count(*) from audit_entries where action = 'ACCOUNT_UPDATED' "
                        + "and resource_type = 'USER' and resource_id = ?",
                Integer.class, id.toString());
    }
}
