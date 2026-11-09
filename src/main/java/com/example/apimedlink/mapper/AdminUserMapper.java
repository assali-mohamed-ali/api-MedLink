package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.AdminUserDto;
import com.example.apimedlink.dto.AdminUserPageDto;
import com.example.apimedlink.service.KeycloakUser;
import com.example.apimedlink.service.KeycloakUserPage;
import com.example.apimedlink.service.RoleGroups;
import java.util.List;
import java.util.Objects;

/** Keycloak's answer to the API shape. Called by controllers: services never see a DTO. */
public final class AdminUserMapper {

    private AdminUserMapper() {}

    public static AdminUserDto toDto(KeycloakUserPage.Entry entry) {
        KeycloakUser user = entry.user();
        List<String> roles = entry.groupPaths().stream().map(RoleGroups::roleOf).filter(Objects::nonNull).toList();
        // A single role is the account's role; several (a failed role change) are shown as such, never one picked
        boolean ambiguous = roles.size() > 1;
        String role = roles.size() == 1 ? roles.get(0) : null;
        return new AdminUserDto(user.id(), user.email(), user.firstName(), user.lastName(), role, roles, ambiguous,
                user.enabled(), user.emailVerified());
    }

    public static AdminUserPageDto toDto(KeycloakUserPage page) {
        return new AdminUserPageDto(page.items().stream().map(AdminUserMapper::toDto).toList(), page.total());
    }
}
