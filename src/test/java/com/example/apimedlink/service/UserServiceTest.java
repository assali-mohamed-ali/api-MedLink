package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.dto.UserDto;
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

        var created = service.create(new UserDto(keycloakId, "alice", "alice@example.com"));

        assertThat(created.id()).isEqualTo(keycloakId);
        assertThat(service.findById(keycloakId).username()).isEqualTo("alice");
    }

    @Test
    void duplicateUsernameIsRejected() {
        service.create(new UserDto(UUID.randomUUID(), "bob", "bob@example.com"));
        assertThatThrownBy(() -> service.create(new UserDto(UUID.randomUUID(), "bob", "bob2@example.com")))
                .isInstanceOf(EntityExistsException.class);
    }

    @Test
    void duplicateIdIsRejected() {
        UUID keycloakId = UUID.randomUUID();
        service.create(new UserDto(keycloakId, "carol", "carol@example.com"));
        assertThatThrownBy(() -> service.create(new UserDto(keycloakId, "carol2", "carol2@example.com")))
                .isInstanceOf(EntityExistsException.class);
    }

    @Test
    void missingIdIsRejected() {
        assertThatThrownBy(() -> service.create(new UserDto(null, "dave", "dave@example.com")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
