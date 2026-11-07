package com.example.apimedlink;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.json.JsonMapper;

/**
 * Review on PR #87 (MLink-97): the dev practitioners never reach a deployed environment. They live in
 * {@code keycloak/dev/dev-practitioners.json}, imported only by the dev-only compose service
 * {@code keycloak-dev-users}; the shared realm file (copied to the servers by the deploy action and mounted
 * by docker-compose.yml) holds none of them, and nothing used to deploy mentions the dev folder.
 */
class RealmImportSeparationTest {

    static final String DEV_FOLDER = "keycloak/dev";
    static final String DEV_USERS_SERVICE = "keycloak-dev-users";

    private static final Path REALM = Path.of("keycloak", "realm-medlink.json");
    private static final Path DEV_USERS = Path.of("keycloak", "dev", "dev-practitioners.json");

    @Test
    void theDevUsersFileHoldsTheThreeDevPractitionersWithFixedIds() {
        List<Map<String, Object>> users = users(DEV_USERS);

        assertThat(users).extracting(user -> user.get("username"))
                .containsExactlyInAnyOrder("generalist.dev", "cardiologist.dev", "pediatrician.dev");
        assertThat(users).allSatisfy(user -> {
            assertThat(user.get("id")).as("fixed id of %s", user.get("username")).isNotNull();
            assertThat(user.get("groups")).isEqualTo(List.of("/practitioners"));
        });
        // The password stays a placeholder, filled in by the dev container from its environment.
        assertThat(read(DEV_USERS)).contains("${KC_DEV_USERS_PASSWORD}").doesNotContain("MedLink-Dev-2026!");
    }

    @Test
    void theSharedRealmHoldsNoneOfTheDevPractitioners() {
        List<Map<String, Object>> devUsers = users(DEV_USERS);
        List<Map<String, Object>> realmUsers = users(REALM);

        assertThat(values(realmUsers, "username")).doesNotContainAnyElementsOf(values(devUsers, "username"));
        assertThat(values(realmUsers, "id")).doesNotContainAnyElementsOf(values(devUsers, "id"));
    }

    @Test
    void nothingUsedToDeployMentionsTheDevFolder() {
        assertThat(read(Path.of("docker-compose.yml"))).doesNotContain(DEV_FOLDER).doesNotContain(DEV_USERS_SERVICE);
        assertThat(read(Path.of(".github", "actions", "deploy-compose", "action.yml"))).doesNotContain(DEV_FOLDER);
    }

    @Test
    void inTheDevComposeFileOnlyTheDevOnlyServiceMountsTheDevFolder() {
        Map<String, Map<String, Object>> services = services(Path.of("compose.yaml"));

        Set<String> mounting = services.entrySet().stream()
                .filter(service -> String.valueOf(service.getValue().get("volumes")).contains(DEV_FOLDER))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(mounting).containsExactly(DEV_USERS_SERVICE);
    }

    private static Set<Object> values(List<Map<String, Object>> users, String key) {
        return users.stream().map(user -> user.get(key)).filter(value -> value != null).collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> users(Path file) {
        Map<String, Object> json = JsonMapper.builder().build().readValue(read(file), Map.class);
        return (List<Map<String, Object>>) json.get("users");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> services(Path file) {
        try (Reader reader = Files.newBufferedReader(file)) {
            return (Map<String, Map<String, Object>>) new Yaml().<Map<String, Object>>load(reader).get("services");
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }
}
