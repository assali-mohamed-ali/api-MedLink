package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * What the sub-sprint 2 login (MLink-55) needs from the realm: the API, as the confidential
 * client {@code medlink-bff}, exchanges an email and a password for tokens, server to server.
 * Other clients and wrong secrets stay refused.
 */
class PasswordGrantFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Grant-Pass-2026!";

    final KeycloakConsole console = new KeycloakConsole();

    @Test
    void theApiCanExchangeAPasswordForTokens() throws Exception {
        String email = uniqueEmail("grant");
        console.createUser(email, PASSWORD);

        HttpResponse<String> response = passwordGrant("medlink-bff", BFF_SECRET, email, PASSWORD);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"access_token\"", "\"refresh_token\"", "\"id_token\"");
    }

    @Test
    void aWrongClientSecretIsRefused() throws Exception {
        String email = uniqueEmail("grant-secret");
        console.createUser(email, PASSWORD);

        assertThat(passwordGrant("medlink-bff", "not-the-secret", email, PASSWORD).statusCode()).isEqualTo(401);
    }

    @Test
    void theAdminServiceAccountCannotLogPeopleIn() throws Exception {
        String email = uniqueEmail("grant-admin");
        console.createUser(email, PASSWORD);

        HttpResponse<String> response = passwordGrant("medlink-admin", ADMIN_CLIENT_SECRET, email, PASSWORD);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("unauthorized_client");
    }
}
