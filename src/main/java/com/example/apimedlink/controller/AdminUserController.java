package com.example.apimedlink.controller;

import com.example.apimedlink.dto.AdminUserDto;
import com.example.apimedlink.dto.AdminUserPageDto;
import com.example.apimedlink.dto.ChangeRoleRequest;
import com.example.apimedlink.dto.ChangeStatusRequest;
import com.example.apimedlink.dto.CreateUserRequest;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.mapper.AdminUserMapper;
import com.example.apimedlink.security.CurrentUser;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.AdminUserService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints 12, 13, 15 and 16 (MLink-69, MLink-70): staff manage accounts. */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminUserService admin;
    private final CurrentUser currentUser;

    public AdminUserController(AdminUserService admin, CurrentUser currentUser) {
        this.admin = admin;
        this.currentUser = currentUser;
    }

    @GetMapping
    @PreAuthorize("hasRole('" + Roles.ADMINISTRATIVE_STAFF + "')")
    public AdminUserPageDto list(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            throw invalid("page", "Le numéro de page doit être positif ou nul.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw invalid("size", "La taille de page doit être comprise entre 1 et 100.");
        }
        return AdminUserMapper.toDto(admin.list(search, page, size));
    }

    @PostMapping
    @PreAuthorize("hasRole('" + Roles.ADMINISTRATIVE_STAFF + "')")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminUserDto create(@Valid @RequestBody CreateUserRequest body) {
        return AdminUserMapper.toDto(
                admin.create(body.email(), body.firstName(), body.lastName(), body.role()));
    }

    @PutMapping("/{id}/role")
    @PreAuthorize("hasRole('" + Roles.ADMINISTRATIVE_STAFF + "')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeRole(@PathVariable UUID id, @Valid @RequestBody ChangeRoleRequest body) {
        admin.changeRole(id, body.role());
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('" + Roles.ADMINISTRATIVE_STAFF + "')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeStatus(@PathVariable UUID id, @Valid @RequestBody ChangeStatusRequest body) {
        admin.setEnabled(id, body.enabled(), currentUser.id());
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Certains paramètres sont invalides.", Map.of(field, message));
    }
}
