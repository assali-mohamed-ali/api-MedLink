package com.example.apimedlink.controller;

import com.example.apimedlink.dto.UserDto;
import com.example.apimedlink.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping
    public List<UserDto> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public UserDto get(@PathVariable Long id) {
        return service.findById(id);
    }

    public record CreateUserRequest(@Valid UserDto user, @NotBlank String password) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto create(@RequestBody @Valid CreateUserRequest req) {
        return service.create(req.user(), req.password());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @GetMapping("/_health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
