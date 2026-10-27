package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.*;

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
import org.springframework.transaction.support.TransactionTemplate;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.UserRepository;

/**
 * The cases a login through a browser cannot trigger on demand (GitHub #29).
 * Real database.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UserProvisioningServiceTest {

    @Autowired
    UserProvisioningService provisioning;

    @Autowired
    UserRepository users;
    @Autowired
    TransactionTemplate tx;

    @Test
    void twoFirstLoginsAtTheSameMomentMakeOneRow() throws Exception {
        UUID id = UUID.randomUUID();
        String email = "race-" + id + "@example.com";
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<User>> logins = List.of(
                    pool.submit(() -> {
                        start.await();
                        return provisioning.provision(id, email, email);
                    }),
                    pool.submit(() -> {
                        start.await();
                        return provisioning.provision(id, email, email);
                    }),
                    pool.submit(() -> {
                        start.await();
                        return provisioning.provision(id, email, email);
                    }),
                    pool.submit(() -> {
                        start.await();
                        return provisioning.provision(id, email, email);
                    }));
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
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ACCOUNT_INCOMPLETE"));
    }

    @Test
    void theEmailIsStoredLowerCase() {
        UUID id = UUID.randomUUID();

        User row = provisioning.provision(id, "Mixed-" + id + "@Example.COM", null);

        assertThat(row.getEmail()).isEqualTo("mixed-" + id + "@example.com");
        assertThat(row.getUsername()).isEqualTo(row.getEmail());
    }

    @Test
    void accountUpdateIsRolledBackWhenAuditFails() {
        UUID id = UUID.randomUUID();
        String originalEmail = "original-" + id + "@example.com";
        provisioning.provision(id, originalEmail, originalEmail);

        // Mock qui plante à chaque record
        AuditService failingAudit = org.mockito.Mockito.mock(AuditService.class);
        org.mockito.Mockito.doThrow(new RuntimeException("audit write failed"))
                .when(failingAudit)
                .record(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());

        UserProvisioningService withFailingAudit = new UserProvisioningService(users, tx, failingAudit);

        String newEmail = "updated-" + id + "@example.com";

        assertThatThrownBy(() -> withFailingAudit.provision(id, newEmail, newEmail))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("audit write failed");

        User reloaded = users.findById(id).orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo(originalEmail);
    }
}
