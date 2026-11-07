package com.example.apimedlink;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The realm file every environment starts from (dev import, tests). Code owner's review of #97: a default group
 * would put every console-made account in it, so a practitioner or staff member would also hold that group's
 * role. Registration adds {@code /patients} itself; a re-export must not bring the default back.
 */
class RealmFileTest {

    private final Map<?, ?> realm = new ObjectMapper().readValue(Path.of("keycloak/realm-medlink.json").toFile(), Map.class);

    @Test
    void newUsersGetNoDefaultGroup() {
        assertThat((Iterable<?>) realm.get("defaultGroups")).as("defaultGroups").isNullOrEmpty();
    }
}
