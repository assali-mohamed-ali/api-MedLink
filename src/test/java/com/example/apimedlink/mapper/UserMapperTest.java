package com.example.apimedlink.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.model.User;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserMapperTest {

    @Test
    void copiesIdUsernameAndEmail() {
        UUID id = UUID.randomUUID();
        User user = User.builder().id(id).username("alice").email("alice@example.com").build();

        var dto = UserMapper.toDto(user);

        assertThat(dto.id()).isEqualTo(id);
        assertThat(dto.username()).isEqualTo("alice");
        assertThat(dto.email()).isEqualTo("alice@example.com");
    }
}
