package com.example.apimedlink.document;

import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.AuditResourceType;
import com.example.apimedlink.repository.PatientDocumentRepository;
import com.example.apimedlink.repository.PatientRepository;
import com.example.apimedlink.security.CurrentUser;
import com.example.apimedlink.security.OwnershipGuard;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.AuditService;
import jakarta.persistence.EntityNotFoundException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * MLink-123/125: the document lifecycle. The bytes never enter Postgres: they are identified by their
 * magic bytes, encrypted with the active key and written to the object store; one transaction then
 * writes the metadata and the upload audit entry. A rollback deletes the stored object, so the store
 * never keeps bytes that no row points to.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_FILENAME_LENGTH = 255;
    private static final String FALLBACK_FILENAME = "document";
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] PNG_MAGIC = {
        (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a
    };

    private final PatientRepository patients;
    private final PatientDocumentRepository documents;
    private final DocumentStorage storage;
    private final DocumentCipher cipher;
    private final DocumentProperties properties;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final OwnershipGuard ownership;
    private final TransactionTemplate transactions;

    public DocumentService(
            PatientRepository patients,
            PatientDocumentRepository documents,
            DocumentStorage storage,
            DocumentCipher cipher,
            DocumentProperties properties,
            AuditService audit,
            CurrentUser currentUser,
            OwnershipGuard ownership,
            TransactionTemplate transactions) {
        this.patients = patients;
        this.documents = documents;
        this.storage = storage;
        this.cipher = cipher;
        this.properties = properties;
        this.audit = audit;
        this.currentUser = currentUser;
        this.ownership = ownership;
        this.transactions = transactions;
    }

    /**
     * Stores the encrypted bytes, then the metadata and the upload audit entry in one transaction.
     *
     * @throws EntityNotFoundException when the patient does not exist (before reading the file)
     * @throws DocumentValidationException for a blank title, an empty file, an unsupported type or a file over 10 MiB
     * @throws StorageException when the object store fails
     * @throws com.example.apimedlink.exception.AuditUnavailableException when the audit entry cannot be written
     */
    public PatientDocument upload(UUID patientId, String title, MultipartFile file) {
        if (!patients.existsById(patientId)) {
            throw new EntityNotFoundException("Patient " + patientId + " not found");
        }
        String cleanTitle = validateTitle(title);
        if (file == null || file.isEmpty()) {
            throw DocumentValidationException.invalid("Le fichier est obligatoire.");
        }
        if (file.getSize() > properties.getMaxSize().toBytes()) {
            throw DocumentValidationException.tooLarge();
        }
        byte[] plaintext;
        try {
            plaintext = file.getBytes();
        } catch (IOException unreadable) {
            throw DocumentValidationException.invalid("Le fichier n'a pas pu être lu.");
        }
        if (plaintext.length == 0) {
            throw DocumentValidationException.invalid("Le fichier est obligatoire.");
        }
        if (plaintext.length > properties.getMaxSize().toBytes()) {
            throw DocumentValidationException.tooLarge();
        }
        String contentType = canonicalContentType(plaintext);
        if (contentType == null) {
            throw DocumentValidationException.unsupportedType();
        }
        String filename = sanitizeFilename(file.getOriginalFilename());
        UUID documentId = UUID.randomUUID();
        byte[] sha256 = sha256(plaintext);
        EncryptedDocument encrypted = cipher.encrypt(plaintext, documentId, patientId);
        String storageKey = storageKey(patientId, documentId);

        AtomicBoolean rollbackCleanupRegistered = new AtomicBoolean(false);
        boolean stored = false;
        try {
            storage.store(storageKey, encrypted.bytes());
            stored = true;
            PatientDocument saved = transactions.execute(status -> {
                registerRollbackDelete(storageKey, rollbackCleanupRegistered);
                PatientDocument document = new PatientDocument(
                        documentId,
                        patientId,
                        currentUser.idOrNull(),
                        cleanTitle,
                        filename,
                        contentType,
                        plaintext.length,
                        HexFormat.of().formatHex(sha256),
                        storageKey,
                        encrypted.keyVersion(),
                        Instant.now());
                PatientDocument persisted = documents.saveAndFlush(document);
                audit.recordInCurrentTransaction(
                        AuditAction.DOCUMENT_UPLOAD,
                        AuditResourceType.PATIENT_DOCUMENT.name(),
                        documentId.toString(),
                        AuditOutcome.SUCCESS);
                return persisted;
            });
            return saved;
        } catch (RuntimeException failure) {
            if (stored && !rollbackCleanupRegistered.get()) {
                deleteQuietly(storageKey);
            }
            throw failure;
        }
    }

    /**
     * The patient's documents, newest first.
     *
     * @throws org.springframework.security.access.AccessDeniedException when a patient asks for another patient
     * @throws EntityNotFoundException when staff asks for a patient that does not exist
     */
    @Transactional(readOnly = true)
    public Page<PatientDocument> list(UUID patientId, int page, int size) {
        if (page < 0 || size < 1 || size > 50) {
            throw DocumentValidationException.invalid("Page ou taille invalide.");
        }
        if (currentUser.hasRole(Roles.ADMINISTRATIVE_STAFF)) {
            if (!patients.existsById(patientId)) {
                throw new EntityNotFoundException("Patient " + patientId + " not found");
            }
        } else {
            ownership.check(patientId);
        }
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return documents.findByPatientId(patientId, pageable);
    }

    /**
     * Reads and verifies one document, then records the read before returning its bytes.
     *
     * @throws EntityNotFoundException when the document does not belong to this patient (or does not exist)
     * @throws StorageException when the stored object is missing or the store fails
     * @throws DocumentIntegrityException when the object no longer decrypts or matches its checksum
     * @throws com.example.apimedlink.exception.AuditUnavailableException when the read cannot be audited
     */
    public DownloadedDocument download(UUID patientId, UUID documentId) {
        // A patient may only read their own documents. A stranger gets the same 404 as an unknown
        // document, before the lookup, so the response cannot confirm that the document exists.
        if (!currentUser.hasRole(Roles.ADMINISTRATIVE_STAFF)
                && !patientId.equals(currentUser.idOrNull())) {
            throw new EntityNotFoundException("Document " + documentId + " not found");
        }
        PatientDocument document = transactions.execute(status -> documents
                .findByIdAndPatientId(documentId, patientId)
                .orElseThrow(() -> new EntityNotFoundException("Document " + documentId + " not found")));
        byte[] stored = storage.load(document.getStorageKey());
        byte[] plaintext = cipher.decrypt(stored, document.getId(), document.getPatientId(), document.getKeyVersion());
        if (!MessageDigest.isEqual(sha256(plaintext), HexFormat.of().parseHex(document.getSha256()))) {
            throw new DocumentIntegrityException("Le document a été modifié.");
        }
        audit.record(
                AuditAction.DOCUMENT_READ,
                AuditResourceType.PATIENT_DOCUMENT.name(),
                document.getId().toString(),
                AuditOutcome.SUCCESS);
        return new DownloadedDocument(plaintext, document.getContentType(), document.getOriginalFilename());
    }

    private static String validateTitle(String title) {
        String clean = title == null ? null : title.trim();
        if (clean == null || clean.isEmpty()) {
            throw DocumentValidationException.invalid("Le titre est obligatoire.");
        }
        if (clean.length() > MAX_TITLE_LENGTH) {
            throw DocumentValidationException.invalid("Le titre ne doit pas dépasser 200 caractères.");
        }
        return clean;
    }

    /** The canonical type from the file's first bytes: the client's Content-Type and extension are not trusted. */
    static String canonicalContentType(byte[] bytes) {
        if (startsWith(bytes, PDF_MAGIC)) {
            return "application/pdf";
        }
        if (startsWith(bytes, PNG_MAGIC)) {
            return "image/png";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        return null;
    }

    /** Keeps one filename, without a path, controls, quotes or backslashes; never blank. */
    static String sanitizeFilename(String raw) {
        if (raw == null) {
            return FALLBACK_FILENAME;
        }
        String normalized = raw.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\p{Cntrl}\"\\\\]", "").trim();
        if (name.isEmpty() || name.chars().allMatch(character -> character == '.')) {
            return FALLBACK_FILENAME;
        }
        if (name.length() > MAX_FILENAME_LENGTH) {
            name = name.substring(0, MAX_FILENAME_LENGTH);
        }
        return name.isBlank() ? FALLBACK_FILENAME : name;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of every JDK", impossible);
        }
    }

    private static String storageKey(UUID patientId, UUID documentId) {
        return patientId + "/" + documentId;
    }

    private void registerRollbackDelete(String storageKey, AtomicBoolean registered) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    deleteQuietly(storageKey);
                }
            }
        });
        registered.set(true);
    }

    private void deleteQuietly(String storageKey) {
        try {
            storage.delete(storageKey);
        } catch (RuntimeException cleanupFailed) {
            log.error("Could not delete the orphaned document object {}", storageKey);
        }
    }
}
