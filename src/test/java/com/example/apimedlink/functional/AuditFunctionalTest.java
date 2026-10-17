package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MLink-59 (story MLink-16, first slice of MLink-17): every refused access (403) writes exactly one
 * row in the audit trail, with the caller and the IP; an anonymous refusal has no user. Ordinary SQL
 * cannot change or delete a row (triggers). The table's owner can still drop the triggers: that
 * boundary is documented in report.md, not tested here.
 *
 * <p>The resource stored is "METHOD /path", redacted (review on PR #56): a segment is kept only when
 * it is a UUID or a short route word, anything else becomes {@code {redacted}}, and the whole is cut
 * at 255 characters.
 */
class AuditFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Audit-Pass-2026!";

    final KeycloakConsole console = new KeycloakConsole();

    @Autowired
    JdbcTemplate db;

    @Test
    void aRefusalWritesExactlyOneAuditRowWithTheCallerAndTheIp() throws Exception {
        String email = uniqueEmail("audited");
        UUID id = console.createUser(email, PASSWORD);
        Browser patient = new Browser();
        patient.apiLogin(email, PASSWORD);

        assertThat(patient.call("/api/users").statusCode()).as("the list is staff-only").isEqualTo(403);

        List<Map<String, Object>> rows = db.queryForList("select * from audit_entries where user_id = ?", id);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("action")).isEqualTo("ACCESS_DENIED");
        assertThat(row.get("outcome")).isEqualTo("DENIED");
        assertThat(row.get("resource_type")).isEqualTo("REQUEST");
        assertThat(row.get("resource_id")).isEqualTo("GET /api/users");
        assertThat(row.get("ip_address")).isNotNull();
        assertThat(row.get("timestamp")).isNotNull();
    }

    @Test
    void anAnonymousRefusalWritesARowWithoutAUser() throws Exception {
        int before = anonymousRows("POST /api/auth/register");
        Browser browser = new Browser();
        browser.call("/api/auth/csrf");

        assertThat(browser.post("/api/auth/register", "{}", false).statusCode())
                .as("no CSRF header").isEqualTo(403);

        assertThat(anonymousRows("POST /api/auth/register")).isEqualTo(before + 1);
    }

    @Test
    void anIdInThePathIsKeptForInvestigations() throws Exception {
        String email = uniqueEmail("audited-id");
        UUID id = console.createUser(email, PASSWORD);
        Browser patient = new Browser();
        patient.apiLogin(email, PASSWORD);
        UUID someoneElse = UUID.randomUUID();

        assertThat(patient.call("/api/users/" + someoneElse).statusCode()).as("not their record").isEqualTo(403);

        assertThat(db.queryForList("select resource_id from audit_entries where user_id = ?", String.class, id))
                .containsExactly("GET /api/users/" + someoneElse);
    }

    @Test
    void personalDataInThePathIsRedactedInTheTrail() throws Exception {
        String route = marker();
        Browser browser = new Browser();
        browser.call("/api/auth/csrf");

        assertThat(browser.post("/api/" + route + "/jean.dupont@example.com", "{}", false).statusCode())
                .as("no CSRF header").isEqualTo(403);

        assertThat(anonymousResources("POST /api/" + route + "/%"))
                .as("the email never reaches the trail")
                .containsExactly("POST /api/" + route + "/{redacted}");
    }

    @Test
    void aVeryLongPathIsCutAndStillRefused() throws Exception {
        String route = marker();
        Browser browser = new Browser();
        browser.call("/api/auth/csrf");

        assertThat(browser.post("/api/" + route + "/x".repeat(400), "{}", false).statusCode())
                .as("the refusal is unchanged").isEqualTo(403);

        assertThat(anonymousResources("POST /api/" + route + "/%"))
                .singleElement()
                .satisfies(resource -> assertThat(resource).startsWith("POST /api/" + route + "/x/x").hasSizeLessThanOrEqualTo(255));
    }

    @Test
    void theAuditTrailCanBeNeitherChangedNorDeleted() {
        UUID probe = UUID.randomUUID();
        db.update("insert into audit_entries (id, timestamp, user_id, action, resource_type, resource_id, ip_address, outcome)"
                + " values (?, now(), null, 'ACCESS_DENIED', 'REQUEST', 'GET /probe', '127.0.0.1', 'DENIED')", probe);

        assertThatThrownBy(() -> db.update("update audit_entries set outcome = 'ALLOWED' where id = ?", probe))
                .as("UPDATE refused by the database").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.update("delete from audit_entries where id = ?", probe))
                .as("DELETE refused by the database").isInstanceOf(DataAccessException.class);
    }

    private int anonymousRows(String resource) {
        return db.queryForObject(
                "select count(*) from audit_entries where user_id is null and resource_id = ?", Integer.class, resource);
    }

    private List<String> anonymousResources(String likePattern) {
        return db.queryForList(
                "select resource_id from audit_entries where user_id is null and resource_id like ?", String.class, likePattern);
    }

    /** A route word no other test uses, so the rows of this test are the only ones under it. */
    private static String marker() {
        return "t" + UUID.randomUUID().toString().substring(0, 8);
    }
}