package com.example.apimedlink.service;

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

    public UserService(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<User> findAll() {
        return users.findAll();
    }

    @Transactional(readOnly = true)
    public User findById(UUID id) {
        return users.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User " + id + " not found"));
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
