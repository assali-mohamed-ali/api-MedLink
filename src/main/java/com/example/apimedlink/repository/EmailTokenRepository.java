package com.example.apimedlink.repository;

import com.example.apimedlink.model.EmailToken;
import com.example.apimedlink.model.EmailToken.Purpose;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EmailTokenRepository extends JpaRepository<EmailToken, UUID> {

    Optional<EmailToken> findByTokenHash(String tokenHash);

    /** Locks the row: two clicks on the same link cannot both use it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from EmailToken t where t.tokenHash = :hash")
    Optional<EmailToken> lockByTokenHash(String hash);

    Optional<EmailToken> findFirstByUserIdAndPurposeOrderByCreatedAtDesc(UUID userId, Purpose purpose);

    @Modifying
    @Query("update EmailToken t set t.usedAt = :now"
            + " where t.userId = :userId and t.purpose = :purpose and t.usedAt is null")
    int markUnusedAsUsed(UUID userId, Purpose purpose, Instant now);
}
