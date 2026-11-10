-- MLink-123/125: the metadata of a patient document. The bytes live in the object store, encrypted
-- with AES-256-GCM (AesDocumentCipher); this table never holds a document or a key. A document is
-- attached to its patient and cannot outlive that patient: deleting a patient that still has
-- documents is refused by the foreign key (the API answers 409 DOCUMENTS_PRESENT).
CREATE TABLE patient_documents (
    id                UUID         PRIMARY KEY,
    patient_id        UUID         NOT NULL,
    -- The staff member who uploaded it. No profile may be deleted while its documents exist, but a
    -- staff account can be: the row keeps the document and loses the author (ON DELETE SET NULL).
    uploaded_by       UUID,
    title             VARCHAR(200) NOT NULL CHECK (btrim(title) <> ''),
    original_filename VARCHAR(255) NOT NULL CHECK (btrim(original_filename) <> ''),
    -- Canonical type found from the magic bytes, never the client's Content-Type.
    content_type      VARCHAR(100) NOT NULL CHECK (content_type IN ('application/pdf', 'image/png', 'image/jpeg')),
    size_bytes        BIGINT       NOT NULL CHECK (size_bytes BETWEEN 1 AND 10485760),
    sha256            VARCHAR(64)  NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    storage_key       VARCHAR(255) NOT NULL UNIQUE,
    key_version       INTEGER      NOT NULL CHECK (key_version > 0),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT patient_documents_patient_id_fkey
        FOREIGN KEY (patient_id) REFERENCES patients (id) ON DELETE RESTRICT,
    CONSTRAINT patient_documents_uploaded_by_fkey
        FOREIGN KEY (uploaded_by) REFERENCES users (id) ON DELETE SET NULL
);

-- The list endpoint always filters by patient and reads newest first; the id keeps the order stable
-- when two rows share the same timestamp.
CREATE INDEX idx_patient_documents_patient_created
    ON patient_documents (patient_id, created_at DESC, id DESC);
