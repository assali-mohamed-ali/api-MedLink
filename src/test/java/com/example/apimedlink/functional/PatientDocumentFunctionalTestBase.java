package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Functional tests for MLink-123/125. They share the one functional-test Spring context and its real
 * database/Keycloak; {@link FunctionalTestBase.DocumentStorageConfig} supplies the real MinIO adapter,
 * which starts the container only on the first document operation.
 */
abstract class PatientDocumentFunctionalTestBase extends FunctionalTestBase {

    @Autowired
    protected JdbcTemplate db;

    protected final KeycloakConsole console = new KeycloakConsole();

    /** A person made in the Keycloak console, logged in once (our users row exists), with a patient profile. */
    protected record TestPatient(UUID id, Browser browser, String email) {}

    protected TestPatient newPatient(String prefix) throws Exception {
        String email = uniqueEmail(prefix);
        UUID id = console.createUser(email, DEV_USERS_PASSWORD, "/patients");
        Browser browser = new Browser();
        assertThat(browser.login(email, DEV_USERS_PASSWORD).call("/api/users/_health").statusCode())
                .as("patient logged in").isEqualTo(200);
        // Console-made accounts have no medical profile: the document API must still see a patient.
        db.update("insert into patients (id, first_name, last_name, birth_date) values (?, ?, ?, ?)"
                        + " on conflict (id) do nothing",
                id, "Test", "Patient", Date.valueOf(LocalDate.of(1990, 1, 1)));
        return new TestPatient(id, browser, email);
    }

    protected Browser staff() throws Exception {
        Browser browser = new Browser();
        assertThat(browser.login("staff.dev@medlink.local", DEV_USERS_PASSWORD)
                        .call("/api/users/_health").statusCode())
                .as("staff logged in").isEqualTo(200);
        return browser;
    }

    protected Browser practitioner() throws Exception {
        Browser browser = new Browser();
        assertThat(browser.login("practitioner.dev@medlink.local", DEV_USERS_PASSWORD)
                        .call("/api/users/_health").statusCode())
                .as("practitioner logged in").isEqualTo(200);
        return browser;
    }

    protected static String documentsPath(UUID patientId) {
        return "/api/patients/" + patientId + "/documents";
    }

    protected static String documentPath(UUID patientId, Object documentId) {
        return documentsPath(patientId) + "/" + documentId;
    }

    /** Uploads a complete multipart request and returns the created DTO as the JSON map React sees. */
    protected static Map<?, ?> upload(Browser browser, UUID patientId, String title, String filename,
            String contentType, byte[] bytes) throws Exception {
        var response = browser.postMultipart(documentsPath(patientId), title, filename, contentType, bytes);
        assertThat(response.statusCode()).as("upload: %s", response.body()).isEqualTo(201);
        return Browser.json(response);
    }
}
