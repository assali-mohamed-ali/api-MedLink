package com.example.apimedlink;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The deploy action ({@code .github/actions/deploy-compose}) runs {@code docker-compose.yml} on the server, where there
 * is no checkout: only the files it copies exist there. A file the compose file mounts but the action does not copy
 * becomes an empty directory on the server (GitHub #57: the database role script was missing, so an existing database
 * kept {@code medlink_app} without a login and a fresh one could not initialise).
 */
class DeployComposeActionTest {

    private static final Path DEPLOY_COMPOSE = Path.of("docker-compose.yml");
    private static final Path ACTION = Path.of(".github", "actions", "deploy-compose", "action.yml");
    private static final String ROLE_SCRIPT = "docker/postgres/initdb/10-app-role.sh";

    @Test
    void everyFileTheDeployComposeFileMountsIsCopiedToTheServer() {
        Set<String> notCopied = mountedHostFiles().stream()
                .filter(file -> lineCopying(file).isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(notCopied).as("files mounted by %s but not copied by %s", DEPLOY_COMPOSE, ACTION).isEmpty();
    }

    @Test
    void theRoleScriptKeepsItsExecutableModeOnTheServer() {
        // Postgres's entrypoint runs an executable *.sh but sources any other one into its own shell
        assertThat(lineCopying(ROLE_SCRIPT)).contains("scp -p ");
    }

    @Test
    void theDeployGivesTheRuntimeRoleItsLoginBeforeTheApiStarts() {
        String action = read(ACTION);
        int database = action.indexOf("up -d --wait postgres");
        int role = action.indexOf("exec -T postgres bash /docker-entrypoint-initdb.d/10-app-role.sh");
        int api = action.indexOf("up -d --no-build --remove-orphans app");

        assertThat(database).as("the database is started and healthy first").isNotNegative();
        assertThat(role).as("then the role script runs (safe to run on every deploy)").isGreaterThan(database);
        assertThat(api).as("and only then the API starts").isGreaterThan(role);
    }

    private static String lineCopying(String file) {
        return read(ACTION).lines()
                .filter(line -> line.contains("scp ") && line.contains(" " + file + " "))
                .findFirst()
                .orElse("");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> mountedHostFiles() {
        Map<String, Map<String, Object>> services;
        try (Reader reader = Files.newBufferedReader(DEPLOY_COMPOSE)) {
            services = (Map<String, Map<String, Object>>) new Yaml().<Map<String, Object>>load(reader).get("services");
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + DEPLOY_COMPOSE, e);
        }
        Set<String> files = services.values().stream()
                .map(service -> (List<Object>) service.get("volumes"))
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(String::valueOf)
                .filter(volume -> volume.startsWith("./"))
                .map(volume -> volume.substring(2, volume.indexOf(':')))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(files).as("the deploy compose file mounts the role script").contains(ROLE_SCRIPT);
        return files;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }
}
