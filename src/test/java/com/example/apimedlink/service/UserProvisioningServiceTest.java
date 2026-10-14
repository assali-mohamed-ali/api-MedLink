package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The cases a login through a browser cannot trigger on demand (GitHub #29). Real database. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UserProvisioningServiceTest {

    @Autowired
    UserProvisioningService provisioning;

    @Autowired
    UserRepository users;

    @Test
    void twoFirstLoginsAtTheSameMomentMakeOneRow() throws Exception {
        UUID id = UUID.randomUUID();
        String email = "race-" + id + "@example.com";
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<User>> logins = List.of(
                    pool.submit(() -> { start.await(); return provisioning.provision(id, email, email); }),
                    pool.submit(() -> { start.await(); return provisioning.provision(id, email, email); }),
                    pool.submit(() -> { start.await(); return provisioning.provision(id, email, email); }),
                    pool.submit(() -> { start.await(); return provisioning.provision(id, email, email); }));
            start.countDown();
            for (Future<User> login : logins) {
                assertThat(login.get().getId()).isEqualTo(id);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(users.findAll().stream().filter(u -> u.getEmail().equals(email))).hasSize(1);
    }

    @Test
    void anAccountWithoutAnEmailIsRefused() {
        assertThatThrownBy(() -> provisioning.provision(UUID.randomUUID(), null, "no-email"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ACCOUNT_INCOMPLETE"));
    }

    @Test
    void theEmailIsStoredLowerCase() {
        UUID id = UUID.randomUUID();

        User row = provisioning.provision(id, "Mixed-" + id + "@Example.COM", null);

        assertThat(row.getEmail()).isEqualTo("mixed-" + id + "@example.com");
        assertThat(row.getUsername()).isEqualTo(row.getEmail());
    }
}
