package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.AdminUserDto;
import com.example.apimedlink.dto.AdminUserPageDto;
import com.example.apimedlink.service.KeycloakUser;
import com.example.apimedlink.service.KeycloakUserPage;
import com.example.apimedlink.service.RoleGroups;

/** Keycloak's answer to the API shape. Called by controllers: services never see a DTO. */
public final class AdminUserMapper {

    private AdminUserMapper() {}

    public static AdminUserDto toDto(KeycloakUserPage.Entry entry) {
        KeycloakUser user = entry.user();
        return new AdminUserDto(user.id(), user.email(), user.firstName(), user.lastName(),
                RoleGroups.roleOf(entry.groupPath()), user.enabled(), user.emailVerified());
    }

    public static AdminUserPageDto toDto(KeycloakUserPage page) {
        return new AdminUserPageDto(page.items().stream().map(AdminUserMapper::toDto).toList(), page.total());
    }
}
