package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.apimedlink.exception.InvalidPasswordException;
import com.example.apimedlink.service.KeycloakAdminService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * MLink-87: the realm remembers the last three passwords, and the API explains the refusal in
 * French.
 */
class PasswordHistoryFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "History-First-2026!";
    static final String NEXT_PASSWORD = "History-Second-2026!";

    @Autowired
    KeycloakAdminService keycloak;

    @Test
    void aPasswordUsedBeforeIsRefusedInFrench() {
        String email = uniqueEmail("history");
        UUID id = keycloak.createUser(email, email, "History", "User", "/patients");
        keycloak.setPassword(id, PASSWORD);
        keycloak.setPassword(id, NEXT_PASSWORD);

        assertThatThrownBy(() -> keycloak.setPassword(id, PASSWORD))
                .isInstanceOfSatisfying(InvalidPasswordException.class, e -> {
                    assertThat(e.getErrorKey()).isEqualTo("invalidPasswordHistoryMessage");
                    assertThat(e.getMessage())
                            .isEqualTo("Le mot de passe doit être différent de vos 3 derniers mots de passe.");
                    assertThat(e.getDetails()).containsEntry("password", e.getMessage());
                    assertThat(e.getMessage()).doesNotContain(PASSWORD);
                });
    }
}
