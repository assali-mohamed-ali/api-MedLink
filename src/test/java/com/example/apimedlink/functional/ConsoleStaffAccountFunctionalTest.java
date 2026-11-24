package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Code owner's review of #97: a practitioner or staff member made in the Keycloak console holds their own group
 * and role only. The realm once added {@code /patients} to every new user, so such accounts also held
 * {@code PATIENT} (one person, one role: CLAUDE.md). Nothing is cleaned up after the console here.
 */
@ExtendWith(OutputCaptureExtension.class)
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

    /** Fail closed: an account an admin left in two MedLink groups gets no session at all, not a mixed one. */
    @ParameterizedTest
    @CsvSource({"/practitioners, /patients", "/administrative-staff, /patients", "/practitioners, /administrative-staff"})
    void anAccountWithTwoRolesIsRefusedAndLeavesNoSessionAnywhere(String first, String second, CapturedOutput output)
            throws Exception {
        String email = uniqueEmail("two-roles");
        UUID id = console.createUserInGroups(email, PASSWORD, List.of(first, second));
        Browser browser = new Browser();

        HttpResponse<String> response = browser.apiLogin(email, PASSWORD);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
        assertThat(Browser.json(response).get("code")).isEqualTo("ACCOUNT_SETUP_INCOMPLETE");
        assertThat(browser.call("/api/users/_health").statusCode()).as("no API session").isEqualTo(401);
        assertThat(console.sessions(id)).as("Keycloak accepted the password: its session is ended again").isEmpty();
        assertThat(output).contains("Login refused for user " + id).contains("more than one role");
    }
}
