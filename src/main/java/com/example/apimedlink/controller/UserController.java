package com.example.apimedlink.controller;

import com.example.apimedlink.dto.UserDto;
import com.example.apimedlink.mapper.UserMapper;
import com.example.apimedlink.security.OwnershipGuard;
import com.example.apimedlink.service.UserService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.example.apimedlink.security.Roles;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService service;
    private final OwnershipGuard ownership;

    public UserController(UserService service, OwnershipGuard ownership) {
        this.service = service;
        this.ownership = ownership;
    }

    @GetMapping
    @PreAuthorize("hasRole('" + Roles.ADMINISTRATIVE_STAFF + "')")
    public List<UserDto> list() {
        return service.findAll().stream().map(UserMapper::toDto).toList();
    }

    @GetMapping("/{id}")
    public UserDto get(@PathVariable UUID id) {
        ownership.check(id); // before the lookup: a stranger gets 403 whether the id exists or not
        return UserMapper.toDto(service.findById(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('" + Roles.ADMINISTRATIVE_STAFF + "')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @GetMapping("/_health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
