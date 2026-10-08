package com.example.apimedlink.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.dto.UserDto;
import com.example.apimedlink.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SecurityTest {

    @Autowired
    UserService users;

    @Autowired
    UserDetailsService userDetailsService;

    @Test
    void passwordIsHashedAndUserDetailsLoads() {
        users.create(new UserDto(null, "carol", "carol@example.com", "ADMIN"), "Secret123!");
        UserDetails details = userDetailsService.loadUserByUsername("carol");
        assertThat(details.getAuthorities()).anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
