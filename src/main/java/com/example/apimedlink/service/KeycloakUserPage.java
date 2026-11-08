package com.example.apimedlink.service;

import java.util.List;

/** One page of Keycloak users with the total number of matches. */
public record KeycloakUserPage(List<Entry> items, long total) {

    /** A user and the paths of every Keycloak group they are in (empty when in none). */
    public record Entry(KeycloakUser user, List<String> groupPaths) {

        /** An account with at most one group path (the usual case, and what the tests build). */
        public Entry(KeycloakUser user, String groupPath) {
            this(user, groupPath == null ? List.of() : List.of(groupPath));
        }

        /** The one group of the account, or null when it has none or several (see {@link #groupPaths}). */
        public String groupPath() {
            return groupPaths.size() == 1 ? groupPaths.get(0) : null;
        }
    }
}
