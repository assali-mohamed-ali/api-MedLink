package com.example.apimedlink.service;

import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.UserRepository;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class UserService {

    private final UserRepository users;
    private final AuditService audit;

    public UserService(UserRepository users, AuditService audit) {
        this.users = users;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<User> findAll() {
        List<User> all = users.findAll();
        audit.record(AuditAction.DATA_READ, "USER", "ALL", AuditOutcome.SUCCESS);
        return all;
    }

    @Transactional(readOnly = true)
    public User findById(UUID id) {
        User user = users.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User " + id + " not found"));
        audit.record(AuditAction.DATA_READ, "USER", id.toString(), AuditOutcome.SUCCESS);
        return user;
    }

    /** {@code id} is the Keycloak user id: the Keycloak account is created first. */
    public User create(UUID id, String username, String email) {
        if (id == null) {
            throw new IllegalArgumentException("User id (the Keycloak user id) is required");
        }
        if (users.existsById(id)) {
            throw new EntityExistsException("User already exists");
        }
        if (users.existsByUsername(username)) {
            throw new EntityExistsException("Username already taken");
        }
        if (users.existsByEmail(email)) {
            throw new EntityExistsException("Email already taken");
        }
        return users.save(User.builder()
                .id(id)
                .username(username)
                .email(email)
                .build());
    }

    public void delete(UUID id) {
        if (!users.existsById(id)) {
            throw new EntityNotFoundException("User " + id + " not found");
        }
        users.deleteById(id);
    }
}
