package com.example.apimedlink.repository;

import com.example.apimedlink.model.UserMfa;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** MLink-62/63: the staff code row. Stub: the tests in UserMfaStoreTest come first. */
@Repository
public class UserMfaStore {

    public record Failures(int count, Instant lockedUntil) {}

    public Optional<UserMfa> find(UUID userId) {
        throw new UnsupportedOperationException("MLink-62");
    }

    public boolean isEnabled(UUID userId) {
        throw new UnsupportedOperationException("MLink-62");
    }

    public boolean saveSecret(UUID userId, String secretEnc) {
        throw new UnsupportedOperationException("MLink-62");
    }

    public boolean markUsed(UUID userId, long step, Instant at) {
        throw new UnsupportedOperationException("MLink-62");
    }

    public Optional<Failures> registerFailure(UUID userId, int max, Instant now, Instant lockUntil) {
        throw new UnsupportedOperationException("MLink-62");
    }
}
