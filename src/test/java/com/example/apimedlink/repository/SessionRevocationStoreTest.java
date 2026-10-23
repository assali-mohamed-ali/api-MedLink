package com.example.apimedlink.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.model.User;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Review of PR #89: the mark that ends a user's API sessions. The end-to-end rule is in
 * PasswordChangeFunctionalTest; here, putting a mark back never erases a newer one.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SessionRevocationStoreTest {

    @Autowired
    SessionRevocationStore store;

    @Autowired
    UserRepository users;

    private UUID aUser() {
        UUID id = UUID.randomUUID();
        users.save(User.builder().id(id).username(id + "@medlink.local").email(id + "@medlink.local").build());
        return id;
    }

    @Test
    void aMarkIsReadBackExactly() {
        UUID id = aUser();

        Instant mark = store.revoke(id);

        assertThat(store.revokedAt(id)).contains(mark);
    }

    /** Two marks in the same microsecond would be equal. */
    private static void later() throws InterruptedException {
        Thread.sleep(2);
    }

    @Test
    void aNewerMarkReplacesTheOlderOne() throws InterruptedException {
        UUID id = aUser();
        store.revoke(id);
        later();

        Instant newer = store.revoke(id);

        assertThat(store.revokedAt(id)).contains(newer);
    }

    @Test
    void undoPutsBackThePreviousMarkOrNone() throws InterruptedException {
        UUID first = aUser();
        store.undo(first, store.revoke(first), Optional.empty());
        assertThat(store.revokedAt(first)).isEmpty();

        UUID second = aUser();
        Instant previous = store.revoke(second);
        later();
        store.undo(second, store.revoke(second), Optional.of(previous));
        assertThat(store.revokedAt(second)).contains(previous);
    }

    @Test
    void undoLeavesANewerMarkAlone() throws InterruptedException {
        UUID id = aUser();
        Instant ours = store.revoke(id);
        later();
        Instant resetMeanwhile = store.revoke(id);

        store.undo(id, ours, Optional.empty());

        assertThat(store.revokedAt(id)).contains(resetMeanwhile);
    }

    @Test
    void aUserWithoutARowHasNoMark() {
        UUID neverLoggedIn = UUID.randomUUID();

        store.revoke(neverLoggedIn);

        assertThat(store.revokedAt(neverLoggedIn)).isEmpty();
    }
}
