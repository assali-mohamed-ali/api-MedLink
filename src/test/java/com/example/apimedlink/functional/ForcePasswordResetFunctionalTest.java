package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.repository.SessionRevocationStore;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * MLink-88: staff force a password change (endpoint 11) and the next login is limited to that change.
 */
class ForcePasswordResetFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Force-Pass-2026!";
    static final String NEXT_PASSWORD = "Forced-Pass-2026!";

    final KeycloakConsole console = new KeycloakConsole();

    @Autowired
    SessionRevocationStore revocations;

    @Test
    void staffForceResetAndTheUserChangesAtNextLogin() throws Exception {
        String email = uniqueEmail("force");
        UUID id = console.createUser(email, PASSWORD);
        Browser user = new Browser().login(email, PASSWORD);
        assertThat(user.call("/api/users/_health").statusCode()).isEqualTo(200);
        Browser staff = new Browser().login("staff.dev@medlink.local", DEV_USERS_PASSWORD);

        HttpResponse<String> forced = staff.postJson(
                "/api/admin/users/" + id + "/force-password-reset", "");

        assertThat(forced.statusCode()).isEqualTo(204);
        assertThat(console.sessions(id)).as("Keycloak sessions ended").isEmpty();
        assertThat(revocations.revokedAt(id)).as("the API's own mark").isPresent();
        assertThat(user.call("/api/users/_health").statusCode()).as("API session follows").isEqualTo(401);
        assertThat(requiredActions(id)).contains("UPDATE_PASSWORD");

        Browser changing = new Browser();
        HttpResponse<String> login = changing.apiLogin(email, PASSWORD);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains("\"passwordChangeRequired\":true").doesNotContain("\"user\"");
        assertThat(changing.call("/api/users/_health").statusCode()).as("no full session yet").isEqualTo(401);

        HttpResponse<String> weak = changing.postJson("/api/auth/password",
                "{\"newPassword\":\"Aa1!short\"}");
        assertThat(weak.statusCode()).isEqualTo(400);
        assertThat(weak.body()).contains("PASSWORD_POLICY");

        HttpResponse<String> changed = changing.postJson("/api/auth/password",
                "{\"newPassword\":\"" + NEXT_PASSWORD + "\"}");
        assertThat(changed.statusCode()).isEqualTo(204);
        assertThat(changing.call("/api/users/_health").statusCode()).as("pending step is gone").isEqualTo(401);
        assertThat(changing.postJson("/api/auth/password",
                "{\"newPassword\":\"Another-Pass-2026!\"}").statusCode()).as("single use").isEqualTo(401);
        assertThat(requiredActions(id)).doesNotContain("UPDATE_PASSWORD");

        assertThat(new Browser().apiLogin(email, PASSWORD).statusCode()).isEqualTo(401);
        HttpResponse<String> after = new Browser().apiLogin(email, NEXT_PASSWORD);
        assertThat(after.statusCode()).isEqualTo(200);
        assertThat(after.body()).contains("\"user\"");
    }

    @Test
    void anotherPendingActionIsRefusedBeforeAnyChange() throws Exception {
        String email = uniqueEmail("force-other");
        UUID id = console.createUser(email, PASSWORD);
        console.update(id, Map.of("requiredActions", List.of("UPDATE_PASSWORD", "UPDATE_PROFILE")));
        Browser browser = new Browser();

        assertThat(browser.apiLogin(email, "Wrong-Pass-2026!").statusCode()).as("wrong password").isEqualTo(401);
        HttpResponse<String> login = browser.apiLogin(email, PASSWORD);

        assertThat(login.statusCode()).isEqualTo(403);
        assertThat(login.body()).contains("\"code\":\"ACCOUNT_SETUP_INCOMPLETE\"");
        assertThat(browser.postJson("/api/auth/password", "{\"newPassword\":\"" + NEXT_PASSWORD + "\"}").statusCode())
                .as("no pending change").isEqualTo(401);
        assertThat(requiredActions(id)).containsExactlyInAnyOrder("UPDATE_PASSWORD", "UPDATE_PROFILE");
    }

    @Test
    void aResetSentAgainIsTheSameAsOnce() throws Exception {
        UUID id = console.createUser(uniqueEmail("force-twice"), PASSWORD);
        Browser staff = new Browser().login("staff.dev@medlink.local", DEV_USERS_PASSWORD);
        String reset = "/api/admin/users/" + id + "/force-password-reset";

        assertThat(staff.postJson(reset, "").statusCode()).isEqualTo(204);
        assertThat(staff.postJson(reset, "").statusCode()).as("staff retry after a 503").isEqualTo(204);

        assertThat(requiredActions(id)).containsExactly("UPDATE_PASSWORD");
    }

    @Test
    void aPatientCannotForceAReset() throws Exception {
        UUID target = console.createUser(uniqueEmail("force-target"), PASSWORD);
        Browser patient = new Browser().login("patient.dev@medlink.local", DEV_USERS_PASSWORD);

        HttpResponse<String> response = patient.postJson(
                "/api/admin/users/" + target + "/force-password-reset", "");

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void anUnknownUserIsNotFoundForStaff() throws Exception {
        Browser staff = new Browser().login("staff.dev@medlink.local", DEV_USERS_PASSWORD);

        HttpResponse<String> response = staff.postJson(
                "/api/admin/users/" + UUID.randomUUID() + "/force-password-reset", "");

        assertThat(response.statusCode()).isEqualTo(404);
    }

    private List<String> requiredActions(UUID id) {
        Map<?, ?> user = (Map<?, ?>) console.read("/users/{id}", id);
        return ((List<?>) user.get("requiredActions")).stream().map(String::valueOf).toList();
    }
}
