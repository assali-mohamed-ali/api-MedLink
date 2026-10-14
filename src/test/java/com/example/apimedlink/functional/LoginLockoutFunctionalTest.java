package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Story MLink-19 (anti-brute-force), against the real Keycloak and its realm file.
 * MLink-66: five wrong passwords lock the account for 15 minutes, the right one included.
 * MLink-67: every failure and the lockout are kept in Keycloak's event log, with the IP.
 */
@SuppressWarnings("unchecked")
class LoginLockoutFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Lockout-Pass-2026!";

    final KeycloakConsole console = new KeycloakConsole();

    /** Five wrong passwords in a row through the login form, as an attacker would. */
    private UUID lockedOutUser() throws Exception {
        String email = uniqueEmail("lockout");
        UUID id = console.createUser(email, PASSWORD);
        for (int attempt = 1; attempt <= 5; attempt++) {
            new Browser().login(email, "Wrong-Pass-" + attempt + "!");
        }
        return id;
    }

    @Test
    void fiveWrongPasswordsLockTheAccountAndTheRightOneIsRefused() throws Exception {
        UUID id = lockedOutUser();
        String email = (String) ((Map<?, ?>) console.read("/users/{id}", id)).get("email");

        Browser browser = new Browser().login(email, PASSWORD);

        assertThat(browser.call("/api/users/_health").statusCode()).as("no session with the right password").isEqualTo(401);
        assertThat(console.lockout(id).get("disabled")).isEqualTo(true);
    }

    @Test
    void theLockLastsFifteenMinutesAndIsNotPermanent() {
        Map<?, ?> realm = console.realm();

        assertThat(realm.get("bruteForceProtected")).isEqualTo(true);
        assertThat(realm.get("failureFactor")).isEqualTo(5);
        assertThat(realm.get("waitIncrementSeconds")).as("first lock already 15 min").isEqualTo(900);
        assertThat(realm.get("maxFailureWaitSeconds")).isEqualTo(900);
        assertThat(realm.get("permanentLockout")).isEqualTo(false);
    }

    @Test
    void eachFailureAndTheLockoutAreInTheEventLogWithTheIp() throws Exception {
        UUID id = lockedOutUser();

        List<?> failures = console.events(id, "LOGIN_ERROR");
        List<?> lockouts = console.events(id, "USER_DISABLED_BY_TEMPORARY_LOCKOUT");

        assertThat(failures).hasSizeGreaterThanOrEqualTo(5)
                .allSatisfy(e -> assertThat(((Map<?, ?>) e).get("ipAddress")).isNotNull());
        assertThat(lockouts).hasSize(1);
    }

    @Test
    void accountChangesMadeInTheConsoleAreKeptToo() {
        Map<?, ?> config = (Map<?, ?>) console.read("/events/config");

        assertThat(config.get("eventsEnabled")).isEqualTo(true);
        assertThat(config.get("eventsExpiration")).as("30 days").isEqualTo(2592000);
        assertThat((List<Object>) config.get("enabledEventTypes")).contains(
                "LOGIN", "LOGIN_ERROR", "LOGOUT", "USER_DISABLED_BY_TEMPORARY_LOCKOUT",
                "UPDATE_PASSWORD", "UPDATE_TOTP", "VERIFY_EMAIL");
        assertThat(config.get("adminEventsEnabled")).isEqualTo(true);
        assertThat(config.get("adminEventsDetailsEnabled")).isEqualTo(true);
    }
}
