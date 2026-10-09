package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.dto.UserDto;
import jakarta.persistence.EntityExistsException;
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
    void createThenFind() {
        var created = service.create(new UserDto(null, "alice", "alice@example.com"));
        assertThat(created.id()).isNotNull();
        assertThat(service.findById(created.id()).username()).isEqualTo("alice");
    }

    @Test
    void duplicateUsernameIsRejected() {
        service.create(new UserDto(null, "bob", "bob@example.com"));
        assertThatThrownBy(() -> service.create(new UserDto(null, "bob", "bob2@example.com")))
                .isInstanceOf(EntityExistsException.class);
    }
}
