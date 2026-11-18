package com.example.apimedlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * GitHub #64 (MLink-164): in staging and prod the API refuses to start without real database credentials. A
 * missing {@code SPRING_DATASOURCE_USERNAME} / {@code SPRING_DATASOURCE_PASSWORD}, or a known placeholder
 * ({@code myuser}, {@code secret}, {@code change-me}...), stops the start before anything connects to the
 * database, and the message names the variable, never its value.
 *
 * <p>The real application and the real profile file, without Docker: the process environment is replaced by the
 * map each test gives, and the database URL points at a closed port. A start that gets past the check therefore
 * fails on the connection, and that failure does not name the variables. Why not "Could not resolve placeholder":
 * {@code spring.datasource.*} is bound as configuration properties, and the binder keeps an unresolved
 * {@code ${...}} as plain text instead of failing.
 *
 * <p>GitHub #57 (MLink-165): two roles. Flyway migrates as the schema owner ({@code SPRING_FLYWAY_USER} /
 * {@code SPRING_FLYWAY_PASSWORD}, same rules), the API connects as the runtime role. The API refuses to start when
 * both are the same user: it would run as the owner, and the audit trail would lose its protection silently.
 */
class DatabaseCredentialsStartupTest {

    static final String USERNAME = "SPRING_DATASOURCE_USERNAME";
    static final String PASSWORD = "SPRING_DATASOURCE_PASSWORD";
    static final String FLYWAY_USER = "SPRING_FLYWAY_USER";
    static final String FLYWAY_PASSWORD = "SPRING_FLYWAY_PASSWORD";

    /** Look like real values: not placeholders, never used anywhere. */
    static final String REAL_USERNAME = "medlink_app";
    static final String REAL_PASSWORD = "q8Vt3nLw-test-only-9fK2";
    static final String REAL_OWNER = "medlink_owner";
    static final String REAL_OWNER_PASSWORD = "Zp4mR7sx-test-only-2hQ6";

    /** Every database variable set to a real-looking value. */
    static Map<String, Object> allReal() {
        return new HashMap<>(Map.of(USERNAME, REAL_USERNAME, PASSWORD, REAL_PASSWORD,
                FLYWAY_USER, REAL_OWNER, FLYWAY_PASSWORD, REAL_OWNER_PASSWORD));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "staging"})
    void noUsernameStopsTheStartAndNamesTheVariable(String profile) {
        Throwable failure = start(profile, Map.of());

        assertThat(rootMessage(failure)).contains(USERNAME);
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "staging"})
    void noPasswordStopsTheStartAndNamesTheVariable(String profile) {
        Throwable failure = start(profile, Map.of(USERNAME, REAL_USERNAME));

        assertThat(rootMessage(failure)).contains(PASSWORD);
    }

    static Stream<Arguments> placeholders() {
        return Stream.of("prod", "staging").flatMap(profile -> Stream.of(
                Arguments.of(profile, USERNAME, "myuser"),
                Arguments.of(profile, USERNAME, "change-me"),
                Arguments.of(profile, USERNAME, ""),
                Arguments.of(profile, PASSWORD, "secret"),
                Arguments.of(profile, PASSWORD, "change-me"),
                Arguments.of(profile, PASSWORD, "change-me-prod"),
                // The longer placeholders of .env*.example and terraform.tfvars*.example: anything "change-me..."
                Arguments.of(profile, PASSWORD, "change-me-staging"),
                Arguments.of(profile, PASSWORD, "change-me-prod-use-vault-or-gh-secrets"),
                Arguments.of(profile, PASSWORD, "change-me-in-local-env"),
                Arguments.of(profile, USERNAME, "CHANGE-ME-user"),
                // The fixed dev values of compose.yaml (code owner's review of #126): anything "dev-only..."
                Arguments.of(profile, PASSWORD, "dev-only-app-role-password"),
                Arguments.of(profile, PASSWORD, "DEV-ONLY-copied-from-a-dev-file"),
                Arguments.of(profile, PASSWORD, "")));
    }

    @ParameterizedTest(name = "{0}: {1}=\"{2}\"")
    @MethodSource("placeholders")
    void aPlaceholderStopsTheStartWithoutEchoingIt(String profile, String variable, String placeholder) {
        Map<String, Object> env = allReal();
        env.put(variable, placeholder);

        String message = rootMessage(start(profile, env));

        assertThat(message).contains(variable);
        if (!placeholder.isEmpty()) {
            assertThat(message).doesNotContain(placeholder);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "staging"})
    void noFlywayUserStopsTheStartAndNamesTheVariable(String profile) {
        Throwable failure = start(profile, Map.of(USERNAME, REAL_USERNAME, PASSWORD, REAL_PASSWORD));

        assertThat(rootMessage(failure)).contains(FLYWAY_USER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "staging"})
    void noFlywayPasswordStopsTheStartAndNamesTheVariable(String profile) {
        Throwable failure = start(profile, Map.of(USERNAME, REAL_USERNAME, PASSWORD, REAL_PASSWORD,
                FLYWAY_USER, REAL_OWNER));

        assertThat(rootMessage(failure)).contains(FLYWAY_PASSWORD);
    }

    static Stream<Arguments> flywayPlaceholders() {
        return Stream.of("prod", "staging").flatMap(profile -> Stream.of(
                Arguments.of(profile, FLYWAY_USER, "myuser"),
                Arguments.of(profile, FLYWAY_USER, "change-me-owner"),
                Arguments.of(profile, FLYWAY_PASSWORD, "secret"),
                Arguments.of(profile, FLYWAY_PASSWORD, "change-me-prod-use-vault-or-gh-secrets"),
                Arguments.of(profile, FLYWAY_PASSWORD, "dev-only-app-role-password"),
                Arguments.of(profile, FLYWAY_PASSWORD, "")));
    }

    @ParameterizedTest(name = "{0}: {1}=\"{2}\"")
    @MethodSource("flywayPlaceholders")
    void aFlywayPlaceholderStopsTheStartWithoutEchoingIt(String profile, String variable, String placeholder) {
        Map<String, Object> env = allReal();
        env.put(variable, placeholder);

        String message = rootMessage(start(profile, env));

        assertThat(message).contains(variable);
        if (!placeholder.isEmpty()) {
            assertThat(message).doesNotContain(placeholder);
        }
    }

    /** The API connecting as the owner would keep every privilege of Flyway: the two roles must differ. */
    @ParameterizedTest
    @ValueSource(strings = {"prod", "staging"})
    void theOwnerAsRuntimeUserStopsTheStartWithoutEchoingIt(String profile) {
        Map<String, Object> env = allReal();
        env.put(USERNAME, REAL_OWNER);

        String message = rootMessage(start(profile, env));

        assertThat(message).contains(USERNAME).contains(FLYWAY_USER).doesNotContain(REAL_OWNER);
    }

    /** Control: real-looking credentials pass the check, so the start goes on and fails on the closed port. */
    @ParameterizedTest
    @ValueSource(strings = {"prod", "staging"})
    void realCredentialsAreNotRefused(String profile) {
        Throwable failure = start(profile, allReal());

        assertThat(rootMessage(failure)).doesNotContain(USERNAME).doesNotContain(PASSWORD)
                .doesNotContain(FLYWAY_USER).doesNotContain(FLYWAY_PASSWORD);
    }

    /** Starts the API under {@code profile} with {@code env} as the whole process environment; returns why it failed. */
    static Throwable start(String profile, Map<String, Object> env) {
        Map<String, Object> processEnv = new HashMap<>(env);
        // Closed port: nothing can answer, whatever runs on this machine.
        processEnv.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:1/medlink");
        // The other secrets without a default outside dev, so that only the database credentials are missing.
        processEnv.put("KEYCLOAK_CLIENT_SECRET", "unused");
        processEnv.put("KEYCLOAK_ADMIN_CLIENT_SECRET", "unused");
        processEnv.put("APP_MFA_ENCRYPTION_KEY", Base64.getEncoder().encodeToString(new byte[32]));

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("test-process-env", processEnv));

        SpringApplicationBuilder app = new SpringApplicationBuilder(ApiMedLinkApplication.class)
                .environment(environment)
                .profiles(profile)
                .properties("server.port=0", "management.server.port=0", "spring.docker.compose.enabled=false",
                        "spring.main.banner-mode=off");
        try (ConfigurableApplicationContext context = app.run()) {
            return fail("the API started under %s with %s", profile, env.keySet());
        } catch (RuntimeException e) {
            return e;
        }
    }

    static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return String.valueOf(root.getMessage());
    }
}
