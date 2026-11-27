package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.exception.EmailTakenException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.repository.RateLimitStore;
import com.example.apimedlink.repository.SessionRevocationStore;
import com.example.apimedlink.repository.UserRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** US-15: password reauthentication, possession of new mailbox, durable identity update, no raw token at rest. */
@Service
public class PatientEmailChangeService {
    private static final Logger log = LoggerFactory.getLogger(PatientEmailChangeService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String RESOURCE = "PATIENT_EMAIL_CHANGE";
    private final JdbcTemplate db;
    private final UserRepository users;
    private final KeycloakAdminService identity;
    private final KeycloakLoginClient login;
    private final MailService mail;
    private final MailThrottle throttle;
    private final RateLimitStore limits;
    private final SessionRevocationStore revocations;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public PatientEmailChangeService(JdbcTemplate db, UserRepository users, KeycloakAdminService identity,
            KeycloakLoginClient login, MailService mail, MailThrottle throttle, RateLimitStore limits,
            SessionRevocationStore revocations, AuditService audit, TransactionTemplate tx) {
        this.db = db;
        this.users = users;
        this.identity = identity;
        this.login = login;
        this.mail = mail;
        this.throttle = throttle;
        this.limits = limits;
        this.revocations = revocations;
        this.audit = audit;
        this.tx = tx;
    }

    public void request(UUID id, String email, String password) {
        rate("request", id, 5);
        String target = email.trim().toLowerCase(Locale.ROOT);
        KeycloakUser account = identity.getUser(id);
        KeycloakTokens proof = login.passwordGrant(account.email(), password);
        try {
            if (!id.toString().equals(proof.accessToken().getSubject())) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CURRENT_PASSWORD",
                        "Le mot de passe actuel est incorrect.");
            }
        } finally {
            login.endSession(proof.refreshToken());
        }
        if (!account.enabled() || !account.emailVerified()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "Ce compte n'est pas disponible.");
        }
        // Same response for existing and free addresses. The old address is always notified for issued requests.
        if (target.equalsIgnoreCase(account.email()) || users.existsByEmail(target) || users.existsByUsername(target)
                || identity.findByEmail(target).isPresent() || !throttle.allow("email-change", target)) {
            return;
        }
        byte[] entropy = new byte[32];
        RANDOM.nextBytes(entropy);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        tx.executeWithoutResult(status -> {
            users.lockLocalAccount(id).orElseThrow(PatientEmailChangeService::invalid);
            if (users.emailChangeInProgress(id)) {
                throw new ApiException(HttpStatus.CONFLICT, "EMAIL_CHANGE_PENDING", "Un changement est déjà en cours.");
            }
            db.update("insert into patient_email_changes(patient_id, old_email, new_email, token_hash, expires_at) "
                    + "values (?, ?, ?, ?, now() + interval '30 minutes') on conflict (patient_id) do update set "
                    + "old_email=excluded.old_email, new_email=excluded.new_email, token_hash=excluded.token_hash, "
                    + "expires_at=excluded.expires_at",
                    id, account.email().toLowerCase(Locale.ROOT), target, EmailTokenService.hash(code));
            audit.recordWithin(AuditAction.ACCOUNT_UPDATED, RESOURCE, id.toString(), AuditOutcome.ATTEMPTED);
        });
        // No medical information or new address in either message. A failed mail can be re-requested after throttle.
        try {
            mail.sendSecurityAlert(account.email(), "Une modification de votre adresse MedLink a été demandée. "
                    + "Si vous n'êtes pas à l'origine de cette demande, contactez votre clinique.");
            mail.sendEmailChangeCode(target, code);
        } catch (RuntimeException failed) {
            log.warn("Email change notification failed for {} ({})", id, failed.getClass().getSimpleName());
        }
    }

    public void confirm(UUID id, String code) {
        rate("confirm", id, 10);
        tx.executeWithoutResult(status -> {
            users.lockLocalAccount(id).orElseThrow(PatientEmailChangeService::invalid);
            int changed = db.update("update patient_email_changes set verified_at=now() "
                    + "where patient_id=? and token_hash=? and verified_at is null and expires_at>now()",
                    id, EmailTokenService.hash(code));
            if (changed != 1) {
                throw invalid();
            }
            revocations.revoke(id);
            audit.recordWithin(AuditAction.ACCOUNT_UPDATED, RESOURCE, id.toString(), AuditOutcome.SUCCESS);
        });
        settleSafely(id);
    }

    @Scheduled(initialDelayString = "30s", fixedDelayString = "30s")
    public void retry() {
        db.update("delete from patient_email_changes where verified_at is null and expires_at <= now()");
        for (UUID id : db.query("select patient_id from patient_email_changes where verified_at is not null "
                + "order by verified_at limit 20", (row, n) -> row.getObject(1, UUID.class))) {
            settleSafely(id);
        }
    }

    private record Change(String oldEmail, String newEmail) {}

    private void settleSafely(UUID id) {
        try {
            tx.executeWithoutResult(status -> settle(id));
        } catch (RuntimeException failed) {
            log.warn("Verified email change pending for {} ({})", id, failed.getClass().getSimpleName());
        }
    }

    private void settle(UUID id) {
        // Use a row lock in the same order as confirmation/provisioning. The verified intent survives any outage.
        users.lockLocalAccount(id).orElseThrow(PatientEmailChangeService::invalid);
        List<Change> rows = db.query("select old_email, new_email from patient_email_changes "
                + "where patient_id=? and verified_at is not null for update", (row, n) ->
                new Change(row.getString(1), row.getString(2)), id);
        if (rows.isEmpty()) return;
        Change change = rows.get(0);
        KeycloakUser account = identity.getUser(id);
        boolean alreadyChanged = change.newEmail().equalsIgnoreCase(account.email());
        if (!alreadyChanged && (!account.enabled() || !account.emailVerified()
                || !change.oldEmail().equalsIgnoreCase(account.email()))) {
            finish(id, AuditOutcome.DENIED);
            return;
        }
        if (!alreadyChanged && (users.existsByEmail(change.newEmail()) || users.existsByUsername(change.newEmail()))) {
            finish(id, AuditOutcome.DENIED);
            return;
        }
        try {
            // Retry the verified, idempotent write even if email already matches: username and
            // verification must agree too before local identity can be committed.
            identity.changeVerifiedEmail(id, change.newEmail());
        } catch (EmailTakenException conflict) {
            finish(id, AuditOutcome.DENIED);
            return;
        }
        // Repeated writes are safe if Keycloak succeeded but the local transaction failed. Never overwrite another user.
        db.update("update users set username=?, email=? where id=?", change.newEmail(), change.newEmail(), id);
        identity.endSessions(id);
        revocations.revoke(id);
        finish(id, AuditOutcome.SUCCESS);
    }

    private void finish(UUID id, AuditOutcome outcome) {
        db.update("delete from patient_email_changes where patient_id=?", id);
        audit.recordInCurrentTransaction(AuditAction.ACCOUNT_UPDATED, RESOURCE, id.toString(), outcome, id);
    }

    private void rate(String action, UUID id, int max) {
        if (limits.hit("email-change:" + action, id.toString(), Duration.ofMinutes(15)).count() > max) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Réessayez dans quelques minutes.");
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "Code invalide ou expiré.");
    }
}
