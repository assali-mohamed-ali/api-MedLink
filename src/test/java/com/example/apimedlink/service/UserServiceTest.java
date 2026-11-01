package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.UserRepository;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UserServiceTest {

    @Autowired
    UserService service;

    @MockitoSpyBean
    UserRepository users;

    @AfterEach
    void resetSpy() {
        Mockito.reset(users);
    }

    @Test
    void createKeepsTheKeycloakIdAsUserId() {
        UUID keycloakId = UUID.randomUUID();

        var created = service.create(keycloakId, "alice", "alice@example.com");

        assertThat(created.getId()).isEqualTo(keycloakId);
        assertThat(service.findById(keycloakId).getUsername()).isEqualTo("alice");
    }

    @Test
    void duplicateUsernameIsRejected() {
        service.create(UUID.randomUUID(), "bob", "bob@example.com");
        assertThatThrownBy(() -> service.create(UUID.randomUUID(), "bob", "bob2@example.com"))
                .isInstanceOf(EntityExistsException.class);
    }

    @Test
    void duplicateIdIsRejected() {
        UUID keycloakId = UUID.randomUUID();
        service.create(keycloakId, "carol", "carol@example.com");
        assertThatThrownBy(() -> service.create(keycloakId, "carol2", "carol2@example.com"))
                .isInstanceOf(EntityExistsException.class);
    }

    @Test
    void missingIdIsRejected() {
        assertThatThrownBy(() -> service.create(null, "dave", "dave@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletingAUserHeldByDocumentsIsAConflictAndKeepsTheUser() {
        User user = newUser("documents");
        doThrow(documentForeignKeyViolation()).when(users).flush();

        assertThatThrownBy(() -> service.delete(user.getId()))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.getStatus().value()).isEqualTo(409);
                    assertThat(failure.getCode()).isEqualTo("DOCUMENTS_PRESENT");
                });

        assertThat(users.existsById(user.getId())).as("the transaction rolled back").isTrue();
    }

    @Test
    void anotherIntegrityViolationIsNotTurnedIntoDocumentsPresent() {
        User user = newUser("other-integrity");
        DataIntegrityViolationException other = new DataIntegrityViolationException(
                "update or delete on table \"users\" violates foreign key constraint \"another_table_user_id_fkey\"",
                new SQLException("another_table_user_id_fkey", "23503"));
        doThrow(other).when(users).flush();

        assertThatThrownBy(() -> service.delete(user.getId())).isSameAs(other);
    }

    @Test
    void aConstraintThatOnlyLooksLikeTheDocumentOneIsNotDocumentsPresent() {
        User user = newUser("similar-constraint");
        DataIntegrityViolationException other = new DataIntegrityViolationException(
                "update or delete on table \"patients\" violates foreign key constraint "
                        + "\"patient_documents_archive_patient_id_fkey\"",
                new SQLException("patient_documents_archive_patient_id_fkey", "23503"));
        doThrow(other).when(users).flush();

        assertThatThrownBy(() -> service.delete(user.getId())).isSameAs(other);
    }

    @Test
    void deletingAUserWithoutDocumentsStillSucceeds() {
        User user = newUser("without-documents");

        service.delete(user.getId());

        assertThat(users.existsById(user.getId())).isFalse();
    }

    @Test
    void deletingAMissingUserIsNotFound() {
        UUID missing = UUID.randomUUID();

        assertThatThrownBy(() -> service.delete(missing))
                .isInstanceOfSatisfying(EntityNotFoundException.class,
                        failure -> assertThat(failure.getMessage()).contains(missing.toString()));
    }

    private User newUser(String prefix) {
        String name = prefix + "-" + UUID.randomUUID();
        return service.create(UUID.randomUUID(), name, name + "@example.com");
    }

    private static DataIntegrityViolationException documentForeignKeyViolation() {
        String message = "update or delete on table \"patients\" violates foreign key constraint "
                + "\"patient_documents_patient_id_fkey\"";
        return new DataIntegrityViolationException(message, new SQLException(message, "23503"));
    }
}
