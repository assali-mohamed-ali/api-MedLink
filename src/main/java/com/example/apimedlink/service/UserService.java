package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.UserRepository;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class UserService {

    private final UserRepository users;
    private final AuditService audit;

    public UserService(UserRepository users, AuditService audit) {
        this.users = users;
        this.audit = audit;
    }

    /**
     * The audit row is written in its own transaction (it must survive a refusal), so it needs its own database
     * connection. This method therefore holds no transaction of its own: the repository reads in its short
     * transaction and gives the connection back before the audit asks for one. Under load, reads that kept their
     * connection while waiting for the audit's would use up the pool (review of #90).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<User> findAll() {
        List<User> all = users.findAll();
        audit.record(AuditAction.DATA_READ, "USER", "ALL", AuditOutcome.SUCCESS);
        return all;
    }

    /** Same rule as {@link #findAll()}: the read has ended, and released its connection, before the audit. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public User findById(UUID id) {
        User user = users.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User " + id + " not found"));
        audit.record(AuditAction.DATA_READ, "USER", id.toString(), AuditOutcome.SUCCESS);
        return user;
    }

    /** {@code id} is the Keycloak user id: the Keycloak account is created first. */
    public User create(UUID id, String username, String email) {
        if (id == null) {
            throw new IllegalArgumentException("User id (the Keycloak user id) is required");
        }
        if (users.existsById(id)) {
            throw new EntityExistsException("User already exists");
        }
        if (users.existsByUsername(username)) {
            throw new EntityExistsException("Username already taken");
        }
        if (users.existsByEmail(email)) {
            throw new EntityExistsException("Email already taken");
        }
        return users.save(User.builder()
                .id(id)
                .username(username)
                .email(email)
                .build());
    }

    /**
     * MLink-123/125: a patient that still has documents cannot be deleted. The foreign key refuses
     * it; the flush is where that refusal surfaces, and the whole delete rolls back. The Keycloak
     * account is not touched here.
     */
    public void delete(UUID id) {
        if (!users.existsById(id)) {
            throw new EntityNotFoundException("User " + id + " not found");
        }
        try {
            users.deleteById(id);
            users.flush();
        } catch (DataIntegrityViolationException failure) {
            if (heldByDocuments(failure)) {
                throw new ApiException(HttpStatus.CONFLICT, "DOCUMENTS_PRESENT",
                        "Ce patient a des documents : supprimez-les avant de supprimer le compte.");
            }
            throw failure;
        }
    }

    private static boolean heldByDocuments(DataIntegrityViolationException failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains("patient_documents_patient_id_fkey")) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }
}
