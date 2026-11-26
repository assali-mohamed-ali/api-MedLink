package com.example.apimedlink.repository;

import com.example.apimedlink.model.AuditAction;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One account operation that reaches Keycloak (MLink-72, review of #122): what was asked, by whom, and how many
 * times it was tried. {@code leaseToken} is the token of the claim that handed it to a worker (null until it is
 * claimed): only that token may close it or put it back to wait. The role and the practitioner profile are the request's own data; what is left to do is
 * decided again when the operation runs, so a retry always works from the current state.
 */
public record AccountOperation(UUID id, AuditAction action, UUID userId, String targetRole, UUID specialtyId,
                               Set<UUID> clinicIds, Boolean enabled, UUID requestedBy, String ipAddress,
                               int attempts, UUID leaseToken) {

    public AccountOperation {
        clinicIds = clinicIds == null ? Set.of() : Set.copyOf(clinicIds);
    }

    public static AccountOperation roleChange(UUID userId, String role, UUID specialtyId, Set<UUID> clinicIds) {
        return new AccountOperation(UUID.randomUUID(), AuditAction.ROLE_CHANGED, userId, role, specialtyId,
                clinicIds, null, null, null, 0, null);
    }

    public static AccountOperation statusChange(UUID userId, boolean enabled) {
        AuditAction action = enabled ? AuditAction.ACCOUNT_ENABLED : AuditAction.ACCOUNT_DISABLED;
        return new AccountOperation(UUID.randomUUID(), action, userId, null, null, Set.of(), enabled, null, null, 0, null);
    }

    /** The same operation, remembering who asked for it and from where (the closing entry reuses both). */
    public AccountOperation requestedBy(UUID actor, String ip) {
        return new AccountOperation(id, action, userId, targetRole, specialtyId, clinicIds, enabled, actor, ip,
                attempts, leaseToken);
    }

    /** True when {@code other} asks for exactly the same change of the same account. */
    public boolean sameRequestAs(AccountOperation other) {
        return action == other.action && userId.equals(other.userId)
                && Objects.equals(targetRole, other.targetRole)
                && Objects.equals(specialtyId, other.specialtyId)
                && clinicIds.equals(other.clinicIds)
                && Objects.equals(enabled, other.enabled);
    }
}
