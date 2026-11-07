package com.example.apimedlink.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.TestcontainersConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

/**
 * The dev seed (MLink-97): loaded by the {@code dev} profile only, its practitioners are the dev Keycloak
 * users of {@code keycloak/dev/dev-practitioners.json} (same ids, so they log in onto their seeded rows),
 * slots look like office hours.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class DevSeedTest {

    private static final String SEED = "db/dev-seed/R__dev_appointment_seed.sql";

    /** Realm username → the specialty its seeded practitioner holds. */
    private static final Map<String, String> PRACTITIONERS = Map.of(
            "generalist.dev", "Médecine générale",
            "cardiologist.dev", "Cardiologie",
            "pediatrician.dev", "Pédiatrie");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Test
    void theThreeClinicsAreSeeded() {
        assertThat(jdbc.queryForList("select city from clinics", String.class))
                .containsExactlyInAnyOrder("Sousse", "Sfax", "Tunis");
    }

    @Test
    void theThreeSpecialtiesAreSeeded() {
        assertThat(jdbc.queryForList("select name from specialties", String.class))
                .containsExactlyInAnyOrderElementsOf(PRACTITIONERS.values());
    }

    @Test
    void eachSeededPractitionerIsTheDevKeycloakUserWithTheSameIdEmailAndSpecialty() throws Exception {
        Map<String, Map<String, Object>> devUsers = devUsersByUsername();

        for (Map.Entry<String, String> expected : PRACTITIONERS.entrySet()) {
            Map<String, Object> realmUser = devUsers.get(expected.getKey());
            assertThat(realmUser).as("dev user %s", expected.getKey()).isNotNull();
            assertThat(realmUser.get("id")).as("fixed id of %s in the dev users file", expected.getKey()).isNotNull();
            assertThat(realmUser.get("groups")).isEqualTo(List.of("/practitioners"));
            UUID id = UUID.fromString((String) realmUser.get("id"));

            Map<String, Object> row = jdbc.queryForMap("""
                    select u.username, u.email, s.name as specialty
                    from practitioners p join users u on u.id = p.id join specialties s on s.id = p.specialty_id
                    where p.id = ?""", id);
            assertThat(row.get("username")).isEqualTo(expected.getKey());
            assertThat(row.get("email")).isEqualTo(realmUser.get("email"));
            assertThat(row.get("specialty")).isEqualTo(expected.getValue());
        }
    }

    @Test
    void theExistingPractitionerDevIsLeftAsItWas() throws Exception {
        Map<String, Object> practitionerDev = realmUsersByUsername().get("practitioner.dev");

        assertThat(practitionerDev).isNotNull().doesNotContainKey("id");
    }

    @Test
    void eachPractitionerHasFreeHalfHourSlotsOnWeekdayOfficeHoursInTunis() {
        assertThat(jdbc.queryForObject("""
                select count(distinct practitioner_id) from slots
                where status = 'FREE' and starts_at > now() and starts_at < now() + interval '14 days'""",
                Integer.class)).isEqualTo(PRACTITIONERS.size());
        assertThat(jdbc.queryForObject(
                "select count(*) from slots where ends_at - starts_at <> interval '30 minutes'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from slots
                where extract(isodow from starts_at at time zone 'Africa/Tunis') in (6, 7)
                   or (starts_at at time zone 'Africa/Tunis')::time < time '08:00'
                   or (ends_at at time zone 'Africa/Tunis')::time > time '17:00'
                   or ((starts_at at time zone 'Africa/Tunis')::time >= time '12:00'
                       and (starts_at at time zone 'Africa/Tunis')::time < time '13:00')""", Integer.class))
                .isZero();
    }

    @Test
    void runningTheSeedAgainAddsNothing() {
        int slots = count("slots");
        int practitioners = count("practitioners");

        new ResourceDatabasePopulator(new ClassPathResource(SEED)).execute(dataSource);

        assertThat(count("slots")).isEqualTo(slots);
        assertThat(count("practitioners")).isEqualTo(practitioners);
        assertThat(count("clinics")).isEqualTo(3);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    /** The users of the shared realm file (every environment). */
    private static Map<String, Map<String, Object>> realmUsersByUsername() throws Exception {
        return usersByUsername(Path.of("keycloak", "realm-medlink.json"));
    }

    /** The dev practitioners, imported in dev only (compose.yaml, service keycloak-dev-users). */
    private static Map<String, Map<String, Object>> devUsersByUsername() throws Exception {
        return usersByUsername(Path.of("keycloak", "dev", "dev-practitioners.json"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> usersByUsername(Path file) throws Exception {
        Map<String, Object> json = JsonMapper.builder().build().readValue(Files.readString(file), Map.class);
        return ((List<Map<String, Object>>) json.get("users")).stream()
                .collect(Collectors.toMap(user -> (String) user.get("username"), user -> user));
    }
}
