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

    @Test
    void aClosedMonthHasOneImmutableReleaseAndRejectsOverlapsAndOtherPartitions() throws Exception {
        Browser staff = new Browser().login("staff.dev@medlink.local", DEV_USERS_PASSWORD);
        String prefix = "/api/admin/stats/export?";
        String month = "from=2002-02-01&to=2002-02-28";
        var first = staff.call(prefix + month + "&indicators=APPOINTMENTS_PER_STATUS");
        assertThat(first.statusCode()).isEqualTo(422);
        assertThat(staff.call(prefix + month + "&indicators=APPOINTMENTS_PER_STATUS").statusCode()).isEqualTo(422);
        var changed = staff.call(prefix + month + "&indicators=APPOINTMENTS_PER_DAY");
        assertThat(changed.statusCode()).isEqualTo(409);
        assertThat(changed.body()).contains("STATS_PARTITION_LOCKED");
        assertThat(staff.call(prefix + "from=2002-02-02&to=2002-02-28").statusCode()).isEqualTo(400);
        assertThat(staff.call(prefix + month + "&indicators=APPOINTMENTS_PER_STATUS,APPOINTMENTS_PER_DAY")
                .statusCode()).isEqualTo(400);
        assertThat(db.queryForObject("select count(*) from monthly_stats_releases where month_start=date '2002-02-01'",
                Integer.class)).isEqualTo(1);
    }

    private long deniedExports() {
        Long count = db.queryForObject("""
                select count(*) from audit_entries
                where action = 'STATS_EXPORTED' and outcome = 'DENIED'
                """, Long.class);
        return count == null ? 0 : count;
    }
}
