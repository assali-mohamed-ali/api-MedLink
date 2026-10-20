package com.example.apimedlink.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.TestcontainersConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The race a functional test cannot stage on demand (GitHub #51): two API instances, each with its own
 * connection pool, counting the same key at the same instant. Every hit gets its own number, none lost.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RateLimitStoreTest {

    @Autowired
    RateLimitStore store;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient db;

    /** A second API instance: same database, its own pool. */
    private HikariDataSource otherInstancePool() {
        HikariDataSource ours = (HikariDataSource) dataSource;
        HikariDataSource other = new HikariDataSource();
        other.setJdbcUrl(ours.getJdbcUrl());
        other.setUsername(ours.getUsername());
        other.setPassword(ours.getPassword());
        other.setMaximumPoolSize(10);
        return other;
    }

    @Test
    void hitsAtTheSameInstantFromTwoInstancesAreEachCountedOnce() throws Exception {
        String subject = UUID.randomUUID().toString();
        int hits = 40;
        try (HikariDataSource pool = otherInstancePool()) {
            RateLimitStore other = new RateLimitStore(JdbcClient.create(pool));
            ExecutorService threads = Executors.newFixedThreadPool(hits);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Integer>> counts = new ArrayList<>();
                for (int i = 0; i < hits; i++) {
                    RateLimitStore instance = i % 2 == 0 ? store : other;
                    counts.add(threads.submit(() -> {
                        start.await();
                        return instance.hit("test", subject, Duration.ofMinutes(5)).count();
                    }));
                }
                start.countDown();
                List<Integer> seen = new ArrayList<>();
                for (Future<Integer> count : counts) {
                    seen.add(count.get());
                }

                assertThat(seen).as("1 to 40, each once: no two hits read the same count")
                        .containsExactlyInAnyOrderElementsOf(java.util.stream.IntStream.rangeClosed(1, hits).boxed().toList());
            } finally {
                threads.shutdownNow();
            }
        }
    }

    @Test
    void aNewWindowStartsOnceTheLastOneHasEnded() throws Exception {
        String subject = UUID.randomUUID().toString();
        store.hit("test", subject, Duration.ofSeconds(1));
        assertThat(store.hit("test", subject, Duration.ofSeconds(1)).count()).isEqualTo(2);

        Thread.sleep(1_100);

        RateLimitStore.Hit fresh = store.hit("test", subject, Duration.ofMinutes(5));
        assertThat(fresh.count()).isEqualTo(1);
        assertThat(fresh.secondsLeft()).isBetween(290L, 300L);
    }

    @Test
    void neitherTheSubjectNorTheEndedWindowsAreKept() throws Exception {
        String subject = "visitor-" + UUID.randomUUID() + "@example.com";
        store.hit("test", subject, Duration.ofSeconds(1));
        assertThat(db.sql("select count(*) from rate_limits where key like :s").param("s", "%" + subject + "%")
                .query(Long.class).single()).as("only a hash is stored").isZero();

        Thread.sleep(1_100);
        store.deleteExpired();

        assertThat(db.sql("select count(*) from rate_limits where expires_at <= now()").query(Long.class).single()).isZero();
    }
}
