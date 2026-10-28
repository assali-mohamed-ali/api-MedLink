package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Code owner's review of #97: a practitioner or staff member made in the Keycloak console holds their own group
 * and role only. The realm once added {@code /patients} to every new user, so such accounts also held
 * {@code PATIENT} (one person, one role: CLAUDE.md). Nothing is cleaned up after the console here.
 */
class ConsoleStaffAccountFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Console-Staff-2026!";

    final KeycloakConsole console = new KeycloakConsole();

    @ParameterizedTest
    @CsvSource({"/practitioners, PRACTITIONER", "/administrative-staff, ADMINISTRATIVE_STAFF"})
    void aConsoleMadeAccountHoldsItsOwnGroupAndRoleOnly(String group, String role) throws Exception {
        String email = uniqueEmail("console-staff");
        UUID id = console.createUser(email, PASSWORD, group);

        assertThat(console.groups(id)).as("groups in Keycloak").containsExactly(group);
        assertThat(new Browser().login(email, PASSWORD).sessionRoles()).as("session roles").containsExactly(role);
    }
}
