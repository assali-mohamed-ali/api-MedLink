package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** MLink-95: access, validation and refusal of the anonymised statistics export, on the real API. */
class StatsExportFunctionalTest extends FunctionalTestBase {

    @Autowired
    JdbcTemplate db;

    final ObjectMapper json = new ObjectMapper();

    @Test
    void aPatientCannotExport() throws Exception {
        Browser patient = new Browser().login("patient.dev@medlink.local", DEV_USERS_PASSWORD);

        assertThat(patient.call("/api/admin/stats/export?from=2026-11-01&to=2026-11-30").statusCode())
                .isEqualTo(403);
    }

    @Test
    void invalidParametersAreRejected() throws Exception {
        Browser staff = new Browser().login("staff.dev@medlink.local", DEV_USERS_PASSWORD);

        assertThat(staff.call("/api/admin/stats/export?from=2026-11-30&to=2026-11-01").statusCode()).isEqualTo(400);
        assertThat(staff.call("/api/admin/stats/export?from=2026-11-01").statusCode()).isEqualTo(400);
        assertThat(staff.call("/api/admin/stats/export?from=nope&to=2026-11-30").statusCode()).isEqualTo(400);
        assertThat(staff.call("/api/admin/stats/export?from=2026-11-01&to=2026-11-30&indicators=NOPE")
                .statusCode()).isEqualTo(400);
    }

    @Test
    void thePeriodWithoutEnoughAppointmentsIsRefusedAndTheRefusalIsTraced() throws Exception {
        Browser staff = new Browser().login("staff.dev@medlink.local", DEV_USERS_PASSWORD);
        long before = deniedExports();

        var response = staff.call("/api/admin/stats/export?from=2001-01-01&to=2001-01-31");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("ANONYMISATION_IMPOSSIBLE");
        assertThat(deniedExports()).isEqualTo(before + 1);
    }

    private long deniedExports() {
        Long count = db.queryForObject("""
                select count(*) from audit_entries
                where action = 'STATS_EXPORTED' and outcome = 'DENIED'
                """, Long.class);
        return count == null ? 0 : count;
    }
}
