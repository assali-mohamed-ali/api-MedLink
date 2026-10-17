package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MLink-58 (story MLink-16): a user reads only their own record, staff read any; every refusal is a
 * 403 with our JSON error ({@code code: "FORBIDDEN"}). A patient gets the same 403 whether the id
 * exists or not, so ids cannot be probed.
 */
class OwnershipFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Owner-Pass-2026!";

    final KeycloakConsole console = new KeycloakConsole();

    @Test
    void aPatientReadsTheirOwnRecord() throws Exception {
        String email = uniqueEmail("owner");
        UUID id = console.createUser(email, PASSWORD);
        Browser patient = loggedIn(email, PASSWORD);

        HttpResponse<String> response = patient.call("/api/users/" + id);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"id\":\"" + id + "\"", "\"email\":\"" + email + "\"");
    }

    @Test
    void aPatientCannotReadSomeoneElsesRecord() throws Exception {
        String otherEmail = uniqueEmail("other");
        UUID other = console.createUser(otherEmail, PASSWORD);
        loggedIn(otherEmail, PASSWORD); // their row exists now
        String email = uniqueEmail("nosy");
        console.createUser(email, PASSWORD);
        Browser patient = loggedIn(email, PASSWORD);

        HttpResponse<String> response = patient.call("/api/users/" + other);

        assertForbidden(response);
        assertThat(response.body()).as("nothing about the other user").doesNotContain(otherEmail);
    }

    @Test
    void aPatientGetsTheSame403WhetherTheIdExistsOrNot() throws Exception {
        String email = uniqueEmail("stranger");
        console.createUser(email, PASSWORD);
        Browser patient = loggedIn(email, PASSWORD);

        assertForbidden(patient.call("/api/users/" + UUID.randomUUID()));
    }

    @Test
    void staffReadAnyRecordAndGet404ForAnUnknownOne() throws Exception {
        String email = uniqueEmail("seen-by-staff");
        UUID id = console.createUser(email, PASSWORD);
        loggedIn(email, PASSWORD);
        Browser staff = loggedIn("staff.dev@medlink.local", DEV_USERS_PASSWORD);

        assertThat(staff.call("/api/users/" + id).statusCode()).isEqualTo(200);
        assertThat(staff.call("/api/users/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
    }

    @Test
    void aRoleRefusalIsOurJsonErrorToo() throws Exception {
        String email = uniqueEmail("not-staff");
        console.createUser(email, PASSWORD);
        Browser patient = loggedIn(email, PASSWORD);

        assertForbidden(patient.call("/api/users")); // the list is staff-only (@PreAuthorize, MLink-57)
    }

    @Test
    void aMissingCsrfHeaderIsOurJsonErrorToo() throws Exception {
        Browser browser = new Browser();
        browser.call("/api/auth/csrf");

        assertForbidden(browser.post("/api/auth/register", "{}", false)); // refused before any controller
    }

    private Browser loggedIn(String email, String password) throws Exception {
        Browser browser = new Browser();
        assertThat(browser.apiLogin(email, password).statusCode()).as("logged in").isEqualTo(200);
        return browser;
    }

    private static void assertForbidden(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
        assertThat(response.body()).contains("\"status\":403", "\"code\":\"FORBIDDEN\"");
    }
}