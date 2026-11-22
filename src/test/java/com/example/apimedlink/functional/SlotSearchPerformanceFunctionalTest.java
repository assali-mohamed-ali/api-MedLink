package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * MLink-99 (story MLink-25): "the results show in less than 2 seconds". 50,000 slots in the database, a patient
 * searches the widest period allowed (31 days, no filter) through the real API. The rows are deleted afterwards,
 * so the other slot searches never see them.
 */
class SlotSearchPerformanceFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Slot-Load-2026!";
    static final int PRACTITIONERS = 50;
    static final int SLOTS_EACH = 1_000;
    /** Far from the other tests' slots (tomorrow onwards): this period holds only this test's rows. */
    static final Instant FIRST = Instant.parse("2031-03-03T08:00:00Z");

    final KeycloakConsole console = new KeycloakConsole();
    final JsonMapper json = JsonMapper.builder().build();
    final List<UUID> practitioners = new ArrayList<>();
    UUID clinic;
    UUID specialty;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aSearchOverFiftyThousandSlotsAnswersInLessThanTwoSeconds() throws Exception {
        insertFiftyThousandSlots();
        Browser patient = patient();
        String search = "/api/slots?from=" + FIRST + "&to=" + FIRST.plus(31, ChronoUnit.DAYS) + "&size=50";

        long start = System.nanoTime();
        HttpResponse<String> response = patient.call(search);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(took).as("search time").isLessThan(Duration.ofSeconds(2));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.readValue(response.body(), Map.class);
        // Every fifth slot is booked: 4 in 5 are free
        assertThat(((Number) body.get("total")).longValue()).isEqualTo(PRACTITIONERS * SLOTS_EACH * 4 / 5);
        assertThat((List<?>) body.get("items")).hasSize(50);
    }

    @AfterEach
    void deleteTheSlots() {
        for (UUID practitioner : practitioners) {
            jdbc.update("delete from slots where practitioner_id = ?", practitioner);
            jdbc.update("delete from practitioner_clinics where practitioner_id = ?", practitioner);
            jdbc.update("delete from practitioners where id = ?", practitioner);
            jdbc.update("delete from users where id = ?", practitioner);
        }
        jdbc.update("delete from clinics where id = ?", clinic);
        jdbc.update("delete from specialties where id = ?", specialty);
    }

    /** 50 practitioners × 1,000 back-to-back 30-minute slots in one clinic; every fifth one is booked. */
    private void insertFiftyThousandSlots() {
        specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        clinic = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, 'Clinique charge', 'Sousse')", clinic);
        for (int i = 0; i < PRACTITIONERS; i++) {
            UUID practitioner = UUID.randomUUID();
            practitioners.add(practitioner);
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
        jdbc.execute("ANALYZE slots");
    }

    /** A patient account made in the console (group /patients), logged in through our login endpoint. */
    private Browser patient() throws Exception {
        String email = uniqueEmail("slot-load");
        console.createUser(email, PASSWORD);
        Browser browser = new Browser();
        assertThat(browser.apiLogin(email, PASSWORD).statusCode()).as("patient logged in").isEqualTo(200);
        return browser;
    }
}
