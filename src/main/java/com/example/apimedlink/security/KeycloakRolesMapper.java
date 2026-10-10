package com.example.apimedlink.security;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.stereotype.Component;

/**
 * Maps the Keycloak realm roles (claim {@value #ROLES_CLAIM}, see keycloak/realm-medlink.json)
 * to {@code ROLE_*} authorities. Any role added in Keycloak is picked up without code change.
 */
@Component
public class KeycloakRolesMapper implements GrantedAuthoritiesMapper {

    public static final String ROLES_CLAIM = "roles";

    @Override
    public Collection<? extends GrantedAuthority> mapAuthorities(
            Collection<? extends GrantedAuthority> authorities) {
        Set<GrantedAuthority> mapped = new HashSet<>(authorities);
        for (GrantedAuthority authority : authorities) {
            if (authority instanceof OidcUserAuthority oidc) {
                List<String> roles = oidc.getIdToken().getClaimAsStringList(ROLES_CLAIM);
                if (roles != null) {
                    roles.forEach(role -> mapped.add(new SimpleGrantedAuthority("ROLE_" + role)));
                }
            }
        }
        return mapped;
    }
}
