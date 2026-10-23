package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SensitiveActionsAuditFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Audit-Read-2026!";

    final KeycloakConsole console = new KeycloakConsole();

    @Autowired
    JdbcTemplate db;

    @Test
    void readingTheUserListIsAudited() throws Exception {
        Browser staff = loggedInStaff();
        int before = auditCount("ALL");

        assertThat(staff.call("/api/users").statusCode()).isEqualTo(200);

        assertThat(auditCount("ALL")).isEqualTo(before + 1);
        assertAudited("ALL");
    }

    @Test
    void readingAUserIsAuditedByItsId() throws Exception {
        String email = uniqueEmail("audit-read");
        UUID id = console.createUser(email, PASSWORD);
        Browser patient = new Browser();
        assertThat(patient.apiLogin(email, PASSWORD).statusCode()).isEqualTo(200);
        Browser staff = loggedInStaff();

        assertThat(staff.call("/api/users/" + id).statusCode()).isEqualTo(200);

        assertThat(auditCount(id.toString())).isEqualTo(1);
        assertAudited(id.toString());
    }

    @Test
    void successfulLoginIsAuditedByTheKeycloakUserId() throws Exception {
        String email = uniqueEmail("audit-login-success");
        UUID id = console.createUser(email, PASSWORD);
        Browser browser = new Browser();

        assertThat(browser.apiLogin(email, PASSWORD).statusCode()).isEqualTo(200);

        assertThat(db.queryForObject(
                "select outcome from audit_entries where action = 'LOGIN' "
                        + "and resource_type = 'USER' and resource_id = ?",
                String.class, id.toString())).isEqualTo("SUCCESS");
    }

    @Test
    void failedLoginIsAuditedWithoutStoringTheSubmittedEmail() throws Exception {
        String email = uniqueEmail("audit-login-failure");
        String unknownEmail = uniqueEmail("audit-login-unknown");
        console.createUser(email, PASSWORD);
        int before = loginFailureAuditRows();

        assertThat(new Browser().apiLogin(email, "Wrong-Pass-2026!").statusCode()).isEqualTo(401);
        assertThat(new Browser().apiLogin(unknownEmail, PASSWORD).statusCode()).isEqualTo(401);

        assertThat(loginFailureAuditRows()).isEqualTo(before + 2);
        assertThat(db.queryForObject(
                "select count(*) from audit_entries where resource_id in (?, ?)",
                Integer.class, email, unknownEmail)).isZero();
    }

    private Browser loggedInStaff() throws Exception {
        Browser staff = new Browser();
        assertThat(staff.apiLogin("staff.dev@medlink.local", DEV_USERS_PASSWORD).statusCode()).isEqualTo(200);
        return staff;
    }

    private int auditCount(String resourceId) {
        return db.queryForObject(
                "select count(*) from audit_entries where action = 'DATA_READ' "
                        + "and resource_type = 'USER' and resource_id = ?",
                Integer.class, resourceId);
    }

    private int loginFailureAuditRows() {
        return db.queryForObject(
                "select count(*) from audit_entries where action = 'LOGIN' "
                        + "and resource_type = 'USER' and resource_id = 'UNKNOWN' and outcome = 'FAILURE'",
                Integer.class);
    }

    private void assertAudited(String resourceId) {
        assertThat(db.queryForObject(
                "select outcome from audit_entries where action = 'DATA_READ' "
                        + "and resource_type = 'USER' and resource_id = ? order by timestamp desc limit 1",
                String.class, resourceId)).isEqualTo("SUCCESS");
        assertThat(db.queryForObject(
                "select user_id from audit_entries where action = 'DATA_READ' "
                        + "and resource_type = 'USER' and resource_id = ? order by timestamp desc limit 1",
                UUID.class, resourceId)).isNotNull();
    }
}
