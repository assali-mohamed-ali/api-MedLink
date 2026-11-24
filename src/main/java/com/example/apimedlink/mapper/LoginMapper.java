package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.LoginResponse;
import com.example.apimedlink.dto.SessionUserDto;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.Login;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** Login to API shape. Called by controllers: services never see a DTO. */
public final class LoginMapper {

    private LoginMapper() {}

    public static LoginResponse toResponse(Login login) {
        return LoginResponse.of(new SessionUserDto(
                login.user().getId(), login.user().getEmail(), login.firstName(), login.lastName(), login.roles()));
    }

    /**
     * The same user object from the session's principal (endpoint 6): the id token the login kept, and the
     * roles the login granted ({@code ROLE_*} authorities, MedLink's roles only).
     */
    public static LoginResponse toResponse(OidcUser principal) {
        List<String> granted = principal.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
        List<String> roles = Roles.ALL.stream().filter(role -> granted.contains("ROLE_" + role)).toList();
        return LoginResponse.of(new SessionUserDto(
                UUID.fromString(principal.getSubject()), principal.getEmail(), principal.getGivenName(),
                principal.getFamilyName(), roles));
    }
}
