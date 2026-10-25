package com.example.apimedlink.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.model.User;
import com.example.apimedlink.model.UserMfa;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * MLink-62/63: the staff code row. Each rule the code step relies on is one SQL statement, so two requests
 * at once can neither use a code twice nor lose a wrong code: the end-to-end rules are in MfaFunctionalTest.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UserMfaStoreTest {

    static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");
    static final Instant LOCK_END = NOW.plus(Duration.ofMinutes(15));
    static final int MAX = 5;

    @Autowired
    UserMfaStore store;

    @Autowired
    UserRepository users;

    private UUID aUser() {
        UUID id = UUID.randomUUID();
        users.save(User.builder().id(id).username(id + "@medlink.local").email(id + "@medlink.local").build());
        return id;
    }

    @Test
    void nothingStoredMeansNoCode() {
        UUID id = aUser();

        assertThat(store.find(id)).isEmpty();
        assertThat(store.isEnabled(id)).isFalse();
    }

    @Test
    void aNewSecretIsStoredButNotYetEnabled() {
        UUID id = aUser();

        assertThat(store.saveSecret(id, "sealed-1")).isTrue();

        UserMfa row = store.find(id).orElseThrow();
        assertThat(row.secretEnc()).isEqualTo("sealed-1");
        assertThat(row.enabled()).isFalse();
        assertThat(row.enrolledAt()).isNull();
        assertThat(row.failedAttempts()).isZero();
        assertThat(store.isEnabled(id)).isFalse();
    }

    @Test
    void aSecondSetupBeforeTheFirstCodeReplacesTheSecret() {
        UUID id = aUser();
        store.saveSecret(id, "sealed-1");

        assertThat(store.saveSecret(id, "sealed-2")).isTrue();

        assertThat(store.find(id).orElseThrow().secretEnc()).isEqualTo("sealed-2");
    }

    @Test
    void theFirstGoodCodeEnablesItAndRecordsTheStep() {
        UUID id = aUser();
        store.saveSecret(id, "sealed-1");

        assertThat(store.markUsed(id, 100, NOW)).isTrue();

        UserMfa row = store.find(id).orElseThrow();
        assertThat(row.enabled()).isTrue();
        assertThat(row.enrolledAt()).isEqualTo(NOW);
        assertThat(row.lastUsedStep()).isEqualTo(100);
        assertThat(store.isEnabled(id)).isTrue();
    }

    @Test
    void anEnabledSecretIsNeverReplaced() {
        UUID id = aUser();
        store.saveSecret(id, "sealed-1");
        store.markUsed(id, 100, NOW);

        assertThat(store.saveSecret(id, "sealed-2")).isFalse();

        assertThat(store.find(id).orElseThrow().secretEnc()).isEqualTo("sealed-1");
    }

    @Test
    void aStepWorksOnceAndNeverAnOlderOne() {
        UUID id = aUser();
        store.saveSecret(id, "sealed-1");
        store.markUsed(id, 100, NOW);

        assertThat(store.markUsed(id, 100, NOW)).isFalse();
        assertThat(store.markUsed(id, 99, NOW)).isFalse();
        assertThat(store.markUsed(id, 101, NOW.plusSeconds(30))).isTrue();

        UserMfa row = store.find(id).orElseThrow();
        assertThat(row.lastUsedStep()).isEqualTo(101);
        assertThat(row.enrolledAt()).isEqualTo(NOW);
    }

    @Test
    void wrongCodesCountUpAndTheFifthLocks() {
        UUID id = aUser();
        store.saveSecret(id, "sealed-1");

        for (int expected = 1; expected < MAX; expected++) {
            UserMfaStore.Failures failures = store.registerFailure(id, MAX, NOW, LOCK_END).orElseThrow();
            assertThat(failures.count()).isEqualTo(expected);
            assertThat(failures.lockedUntil()).isNull();
        }
        UserMfaStore.Failures fifth = store.registerFailure(id, MAX, NOW, LOCK_END).orElseThrow();

        assertThat(fifth.count()).isEqualTo(MAX);
        assertThat(fifth.lockedUntil()).isEqualTo(LOCK_END);
        assertThat(store.find(id).orElseThrow().lockedUntil()).isEqualTo(LOCK_END);
    }

    @Test
    void whileLockedNothingCountsAndNoCodeIsAccepted() {
        UUID id = aUser();
        store.saveSecret(id, "sealed-1");
        lock(id);

        UserMfaStore.Failures more = store.registerFailure(id, MAX, NOW.plusSeconds(60), LOCK_END.plusSeconds(60))
                .orElseThrow();

        assertThat(more.count()).isEqualTo(MAX);
        assertThat(more.lockedUntil()).isEqualTo(LOCK_END);
        assertThat(store.markUsed(id, 100, NOW.plusSeconds(60))).isFalse();
        assertThat(store.find(id).orElseThrow().enabled()).isFalse();
    }

    @Test
    void afterTheLockAGoodCodeWorksAndClearsTheCount() {
        UUID id = aUser();
        store.saveSecret(id, "sealed-1");
        lock(id);

        assertThat(store.markUsed(id, 200, LOCK_END)).isTrue();

        UserMfa row = store.find(id).orElseThrow();
        assertThat(row.failedAttempts()).isZero();
        assertThat(row.lockedUntil()).isNull();
    }

    @Test
    void afterTheLockAWrongCodeStartsANewCount() {
        UUID id = aUser();
        store.saveSecret(id, "sealed-1");
        lock(id);

        UserMfaStore.Failures failures = store.registerFailure(id, MAX, LOCK_END, LOCK_END.plusSeconds(900))
                .orElseThrow();

        assertThat(failures.count()).isEqualTo(1);
        assertThat(failures.lockedUntil()).isNull();
    }

    @Test
    void aGoodCodeClearsEarlierWrongOnes() {
        UUID id = aUser();
        store.saveSecret(id, "sealed-1");
        store.registerFailure(id, MAX, NOW, LOCK_END);
        store.registerFailure(id, MAX, NOW, LOCK_END);

        store.markUsed(id, 100, NOW);

        assertThat(store.find(id).orElseThrow().failedAttempts()).isZero();
    }

    @Test
    void aWrongCodeWithoutASecretCountsNothing() {
        assertThat(store.registerFailure(aUser(), MAX, NOW, LOCK_END)).isEmpty();
    }

    @Test
    void tenWrongCodesAtOnceCountFiveAndLockOnce() throws Exception {
        UUID id = aUser();
        store.saveSecret(id, "sealed-1");

        List<UserMfaStore.Failures> results = atOnce(10, () -> store.registerFailure(id, MAX, NOW, LOCK_END).orElseThrow());

        assertThat(results).extracting(UserMfaStore.Failures::count)
                .containsExactlyInAnyOrder(1, 2, 3, 4, 5, 5, 5, 5, 5, 5);
        UserMfa row = store.find(id).orElseThrow();
        assertThat(row.failedAttempts()).isEqualTo(MAX);
        assertThat(row.lockedUntil()).isEqualTo(LOCK_END);
    }

    @Test
    void theSameCodeTwiceAtOnceWorksOnce() throws Exception {
        UUID id = aUser();
        store.saveSecret(id, "sealed-1");

        List<Boolean> results = atOnce(2, () -> store.markUsed(id, 100, NOW));

        assertThat(results).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void theRowGoesWithTheUser() {
        UUID id = aUser();
        store.saveSecret(id, "sealed-1");

        users.deleteById(id);

        assertThat(store.find(id)).isEmpty();
    }

    private void lock(UUID id) {
        for (int i = 0; i < MAX; i++) {
            store.registerFailure(id, MAX, NOW, LOCK_END);
        }
        assertThat(store.find(id).orElseThrow().lockedUntil()).isEqualTo(LOCK_END);
    }

    /** Runs the call on {@code threads} threads released together, and returns every result. */
    private static <T> List<T> atOnce(int threads, Callable<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
