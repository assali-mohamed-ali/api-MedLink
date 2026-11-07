package com.example.apimedlink.service;

import java.util.List;

/** One page of Keycloak users with the total number of matches. */
public record KeycloakUserPage(List<Entry> items, long total) {

    /**
     * A user and the path of the Keycloak group they are in (null when in none).
     */
    public record Entry(KeycloakUser user, String groupPath) {
    }
}
