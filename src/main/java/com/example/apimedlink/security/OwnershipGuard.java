package com.example.apimedlink.security;

import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * "Does this data belong to the caller?" (MLink-58): the owner and administrative staff pass,
 * everyone else gets 403. Call it before reading the data, so a refusal never tells whether it exists.
 */
@Component
public class OwnershipGuard {

    private final CurrentUser currentUser;

    public OwnershipGuard(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    public void check(UUID ownerId) {
        if (currentUser.hasRole(Roles.ADMINISTRATIVE_STAFF) || currentUser.id().equals(ownerId)) {
            return;
        }
        throw new AccessDeniedException("Not the owner of " + ownerId);
    }
}
