package com.example.apimedlink.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

class KeycloakRolesMapperTest {

    private final KeycloakRolesMapper mapper = new KeycloakRolesMapper();

    private static OidcUserAuthority authorityWithClaims(Map<String, Object> claims) {
        OidcIdToken token = new OidcIdToken(
                "token", Instant.now(), Instant.now().plusSeconds(60), claims);
        return new OidcUserAuthority(token);
    }

    @Test
    void realmRolesBecomeSpringRoles() {
        var authority = authorityWithClaims(Map.of("sub", "u1", "roles", List.of("PRACTITIONER", "ADMINISTRATIVE_STAFF")));

        var mapped = mapper.mapAuthorities(List.of(authority)).stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        assertThat(mapped).contains("ROLE_PRACTITIONER", "ROLE_ADMINISTRATIVE_STAFF");
    }

    @Test
    void missingRolesClaimAddsNothing() {
        var authority = authorityWithClaims(Map.of("sub", "u1"));

        assertThat(mapper.mapAuthorities(List.of(authority))).hasSize(1);
    }
}
