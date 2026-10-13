package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.apimedlink.TestcontainersConfiguration;
import jakarta.persistence.EntityExistsException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UserServiceTest {

    @Autowired
    UserService service;

    @Test
    void createKeepsTheKeycloakIdAsUserId() {
        UUID keycloakId = UUID.randomUUID();

        var created = service.create(keycloakId, "alice", "alice@example.com");

        assertThat(created.getId()).isEqualTo(keycloakId);
        assertThat(service.findById(keycloakId).getUsername()).isEqualTo("alice");
    }

    @Test
    void duplicateUsernameIsRejected() {
        service.create(UUID.randomUUID(), "bob", "bob@example.com");
        assertThatThrownBy(() -> service.create(UUID.randomUUID(), "bob", "bob2@example.com"))
                .isInstanceOf(EntityExistsException.class);
    }

    @Test
    void duplicateIdIsRejected() {
        UUID keycloakId = UUID.randomUUID();
        service.create(keycloakId, "carol", "carol@example.com");
        assertThatThrownBy(() -> service.create(keycloakId, "carol2", "carol2@example.com"))
                .isInstanceOf(EntityExistsException.class);
    }

    @Test
    void missingIdIsRejected() {
        assertThatThrownBy(() -> service.create(null, "dave", "dave@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
