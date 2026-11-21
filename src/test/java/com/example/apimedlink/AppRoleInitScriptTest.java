package com.example.apimedlink;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * GitHub #57 (MLink-165), code owner's review of #126: the script that creates the runtime role never puts
 * {@code APP_DB_PASSWORD} on a command line. Arguments can be read by other processes (process listings,
 * diagnostics); psql reads the password from its own environment with {@code \getenv} instead.
 *
 * <p>That the role is still created with this password, and can log in, is proven by every Testcontainers test:
 * their database runs this script and the API connects as {@code medlink_app}.
 */
class AppRoleInitScriptTest {

    static final Path SCRIPT = Path.of("docker/postgres/initdb/10-app-role.sh");

    /** {@code $APP_DB_PASSWORD} or {@code ${APP_DB_PASSWORD...}}: the shell expanding the password. */
    static final Pattern EXPANSION = Pattern.compile("\\$\\{?APP_DB_PASSWORD");

    @Test
    void thePasswordIsExpandedOnlyToCheckThatItIsSet() throws IOException {
        List<String> expanding = lines().stream()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .filter(line -> EXPANSION.matcher(line).find())
                .toList();

        assertThat(expanding).as("lines of %s where the shell expands APP_DB_PASSWORD", SCRIPT)
                .singleElement().asString().contains("${APP_DB_PASSWORD:?");
    }

    @Test
    void psqlReadsThePasswordFromItsEnvironment() throws IOException {
        assertThat(lines()).as(SCRIPT.toString())
                .anyMatch(line -> line.strip().equals("\\getenv app_password APP_DB_PASSWORD"))
                .noneMatch(line -> line.contains("-v app_password"));
    }

    static List<String> lines() throws IOException {
        return Files.readAllLines(SCRIPT);
    }
}
