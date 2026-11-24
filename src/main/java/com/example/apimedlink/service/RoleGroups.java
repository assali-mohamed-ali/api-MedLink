package com.example.apimedlink.service;

import com.example.apimedlink.security.Roles;
import java.util.Map;

/** The Keycloak group of each MedLink role (keycloak/realm-medlink.json): the group carries the role. */
public final class RoleGroups {

    private static final Map<String, String> PATH_BY_ROLE = Map.of(
            Roles.PATIENT, "/patients",
            Roles.PRACTITIONER, "/practitioners",
            Roles.ADMINISTRATIVE_STAFF, "/administrative-staff");

    private RoleGroups() {}

    /** The group path of a role, or null for an unknown role. */
    public static String pathOf(String role) {
        return role == null ? null : PATH_BY_ROLE.get(role);
    }

    /** The role carried by a group path, or null when the user is in no MedLink group. */
    public static String roleOf(String groupPath) {
        return PATH_BY_ROLE.entrySet().stream()
                .filter(entry -> entry.getValue().equals(groupPath))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }
}
