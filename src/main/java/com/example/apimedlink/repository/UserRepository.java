package com.example.apimedlink.repository;

import com.example.apimedlink.model.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    @Query(value = "select id from users where id = :id for update", nativeQuery = true)
    Optional<UUID> lockLocalAccount(@Param("id") UUID id);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}
