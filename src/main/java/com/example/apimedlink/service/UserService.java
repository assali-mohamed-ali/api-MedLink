package com.example.apimedlink.service;

import com.example.apimedlink.dto.UserDto;
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
    public List<UserDto> findAll() {
        return users.findAll().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public UserDto findById(UUID id) {
        return toDto(users.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User " + id + " not found")));
    }

    public UserDto create(UserDto dto) {
        if (users.existsByUsername(dto.username())) {
            throw new EntityExistsException("Username already taken");
        }
        if (users.existsByEmail(dto.email())) {
            throw new EntityExistsException("Email already taken");
        }
        User saved = users.save(User.builder()
                .username(dto.username())
                .email(dto.email())
                .build());
        return toDto(saved);
    }

    public void delete(UUID id) {
        if (!users.existsById(id)) {
            throw new EntityNotFoundException("User " + id + " not found");
        }
        users.deleteById(id);
    }

    private UserDto toDto(User u) {
        return new UserDto(u.getId(), u.getUsername(), u.getEmail());
    }
}
