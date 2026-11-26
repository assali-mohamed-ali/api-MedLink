package com.example.apimedlink.document;

import java.util.UUID;

/**
 * MLink-123/125: seals a document before it is stored. The document id and patient id are bound to
 * the payload, so ciphertext copied to another row cannot be read.
 */
public interface DocumentCipher {

    EncryptedDocument encrypt(byte[] plaintext, UUID documentId, UUID patientId);

    /**
     * @throws DocumentIntegrityException when the payload was changed, copied to another row or
     *     sealed with an unknown key version
     */
    byte[] decrypt(byte[] payload, UUID documentId, UUID patientId, int keyVersion);
}
