package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/**
 * MLink-83: a session left open is dead after 15 minutes without activity, on both sides: our
 * API session and Keycloak's SSO session, which our token renewals keep alive while the person
 * works. Waiting 15 minutes is MLink-86's expiry test; here the running values are checked.
 */
class SessionIdleTimeoutFunctionalTest extends FunctionalTestBase {

    @Value("${server.servlet.session.timeout}")
    Duration apiSessionTimeout;

    final KeycloakConsole console = new KeycloakConsole();

    @Test
    void theApiSessionEndsAfterFifteenIdleMinutes() {
        assertThat(apiSessionTimeout).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void keycloaksSessionIsAlignedFifteenMinutesIdleTenHoursMax() {
        Map<?, ?> realm = console.realm();

        assertThat(realm.get("ssoSessionIdleTimeout")).isEqualTo(900);
        assertThat(realm.get("ssoSessionMaxLifespan")).isEqualTo(36000);
    }
}
