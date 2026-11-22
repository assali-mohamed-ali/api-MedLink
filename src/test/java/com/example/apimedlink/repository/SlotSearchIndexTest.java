package com.example.apimedlink.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MLink-99 (story MLink-25): on 50,000 slots, the slot search reads an index in start order and stops at the
 * page, instead of reading the whole table (Seq Scan) and sorting it. The query is the one of
 * {@code SlotService.searchFree} with no filter, the widest search a patient can make.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class SlotSearchIndexTest {

    static final int PRACTITIONERS = 50;
    static final int SLOTS_EACH = 1_000;
    static final Instant FIRST = Instant.parse("2031-03-03T08:00:00Z");

    static final String SEARCH = """
            SELECT s.id, s.starts_at, s.ends_at, c.name, u.id, p.first_name, p.last_name, sp.name
            FROM slots s
            JOIN practitioners p ON p.id = s.practitioner_id
            JOIN users u ON u.id = p.id
            JOIN specialties sp ON sp.id = p.specialty_id
            JOIN clinics c ON c.id = s.clinic_id
            WHERE s.status = 'FREE'
              AND s.starts_at >= timestamptz '2031-03-03 08:00:00+00'
              AND s.starts_at < timestamptz '2031-04-03 08:00:00+00'
            ORDER BY s.starts_at, s.id
            LIMIT 20""";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theSearchReadsTheFreeSlotsIndexInStartOrderNotTheWholeTable() {
        insertFiftyThousandSlots();
        jdbc.execute("ANALYZE slots");

        String plan = String.join("\n", jdbc.queryForList("EXPLAIN " + SEARCH, String.class));

        assertThat(plan).as(plan).contains("slots_free_by_start").doesNotContain("Seq Scan on slots");
    }

    /** 50 practitioners × 1,000 back-to-back 30-minute slots in one clinic; every fifth one is booked. */
    private void insertFiftyThousandSlots() {
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        UUID clinic = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, 'Clinique charge', 'Sousse')", clinic);
        for (int i = 0; i < PRACTITIONERS; i++) {
            UUID practitioner = UUID.randomUUID();
            jdbc.update("insert into users (id, username, email) values (?, ?, ?)",
                    practitioner, "load-" + practitioner, "load-" + practitioner + "@example.com");
            jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Load', 'Test', ?)",
                    practitioner, specialty);
            jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", practitioner, clinic);
            jdbc.update("""
                    insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status)
                    select gen_random_uuid(), ?, ?, ?::timestamptz + n * interval '30 minutes',
                           ?::timestamptz + (n + 1) * interval '30 minutes',
                           case when n % 5 = 0 then 'BOOKED' else 'FREE' end
                    from generate_series(0, ?) as n""",
                    practitioner, clinic, Timestamp.from(FIRST), Timestamp.from(FIRST), SLOTS_EACH - 1);
        }
        assertThat(jdbc.queryForObject("select count(*) from slots where clinic_id = ?", Integer.class, clinic))
                .isEqualTo(PRACTITIONERS * SLOTS_EACH);
    }
}
