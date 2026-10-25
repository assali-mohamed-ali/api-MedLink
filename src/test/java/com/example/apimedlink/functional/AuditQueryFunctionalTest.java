package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** MLink-115: administrative audit-log reading, filtering, and pagination. */
class AuditQueryFunctionalTest extends FunctionalTestBase {

    @Autowired
    JdbcTemplate db;

    final ObjectMapper json = new ObjectMapper();

    @Test
    void staffCanFilterByDateUserAndActionAndPaginateNewestFirst() throws Exception {
        UUID targetUser = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        Instant beforeRange = Instant.parse("2026-10-24T10:00:00Z");
        Instant rangeStart = Instant.parse("2026-10-24T11:00:00Z");
        Instant rangeEnd = Instant.parse("2026-10-24T12:00:00Z");
        insertAudit(targetUser, beforeRange, "GET /api/users");
        insertAudit(targetUser, rangeStart, "GET /api/users");
        insertAudit(targetUser, rangeEnd, "GET /api/users/{id}");
        insertAudit(otherUser, rangeEnd, "GET /api/users");

        Browser staff = new Browser().login("staff.dev@medlink.local", DEV_USERS_PASSWORD);
        String filters = "?from=" + encode(rangeStart.toString())
                + "&to=" + encode(rangeEnd.toString())
                + "&userId=" + targetUser
                + "&action=ACCESS_DENIED";

        JsonNode firstPage = json.readTree(staff.call(
                "/api/admin/audit-entries" + filters + "&page=0&size=1").body());
        assertThat(firstPage.path("items")).hasSize(1);
        assertThat(firstPage.path("items").get(0).path("userId").asText()).isEqualTo(targetUser.toString());
        assertThat(firstPage.path("items").get(0).path("resourceId").asText()).isEqualTo("GET /api/users/{id}");
        assertThat(firstPage.path("page").asInt()).isZero();
        assertThat(firstPage.path("size").asInt()).isEqualTo(1);
        assertThat(firstPage.path("totalElements").asLong()).isEqualTo(2);
        assertThat(firstPage.path("totalPages").asInt()).isEqualTo(2);

        JsonNode secondPage = json.readTree(staff.call(
                "/api/admin/audit-entries" + filters + "&page=1&size=1").body());
        assertThat(secondPage.path("items")).hasSize(1);
        assertThat(secondPage.path("items").get(0).path("resourceId").asText()).isEqualTo("GET /api/users");
        assertThat(secondPage.path("page").asInt()).isEqualTo(1);
        assertThat(secondPage.path("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    void invalidDateRangeAndPageSizeAreRejected() throws Exception {
        Browser staff = new Browser().login("staff.dev@medlink.local", DEV_USERS_PASSWORD);

        assertThat(staff.call("/api/admin/audit-entries?from=2026-10-25T12:00:00Z"
                + "&to=2026-10-25T11:00:00Z").statusCode()).isEqualTo(400);
        assertThat(staff.call("/api/admin/audit-entries?size=0").statusCode()).isEqualTo(400);
        assertThat(staff.call("/api/admin/audit-entries?size=101").statusCode()).isEqualTo(400);
        assertThat(staff.call("/api/admin/audit-entries?page=-1").statusCode()).isEqualTo(400);
        assertThat(staff.call("/api/admin/audit-entries?action=UNKNOWN_ACTION").statusCode()).isEqualTo(400);
    }

    private void insertAudit(UUID userId, Instant timestamp, String resourceId) {
        db.update("""
                insert into audit_entries
                    (id, timestamp, user_id, action, resource_type, resource_id, ip_address, outcome)
                values (?, ?, ?, 'ACCESS_DENIED', 'REQUEST', ?, '198.18.0.1', 'DENIED')
                """,
                UUID.randomUUID(), Timestamp.from(timestamp), userId, resourceId);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
