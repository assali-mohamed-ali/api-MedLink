# 0002 — Store patient documents as encrypted objects with metadata in Postgres

| | |
|---|---|
| Status | **Accepted and implemented** |
| Date | 2026-11-02 |
| Tracking | YouTrack epic MLink-9 (MLink-123 encrypted upload/storage, MLink-125 list/download) |
| Scope | `document/` package, `V14__patient_documents.sql`, document tests |

## Decision

Patient document **bytes live in an S3-compatible object store, encrypted by the API**. Postgres holds
only metadata: owner, author, title, original filename, canonical content type, plaintext size, SHA-256,
object key and key version. The object key is `{patientId}/{documentId}`.

The API does the encryption itself with AES-256-GCM before writing to MinIO. Each object is
`[version][12-byte IV][ciphertext + 128-bit tag]`; the associated data is
`documentId|patientId|keyVersion`. The row's `key_version` selects the key, so rotation adds a new version
without rewriting existing documents. On download the API decrypts, verifies the AAD and compares the
plaintext SHA-256 with the recorded hash before returning anything.

Documents are **immutable through the API**: there is no update endpoint and no explicit delete endpoint.
Upload and download are audited. A failed upload transaction deletes the object it just stored; a patient
with documents cannot be deleted (`409 DOCUMENTS_PRESENT`), and a deleted staff author only loses the link
to the row (`uploaded_by` becomes `NULL`).

The functional tests use a **lazy test-storage adapter**: `FunctionalTestBase.DocumentStorageConfig`
registers a `@Primary` `DocumentStorage` whose real `MinioDocumentStorage` delegate is created on the first
storage operation. This keeps the one shared functional-test Spring context and its fixed API port, and
only starts the pinned MinIO container for tests that actually touch documents. The failure-injection test
uses its own MinIO instance because it deletes and tampers with objects.

## Context

MLink-9 adds a patient document upload/list/download feature to a health API that already has a strict
BFF security model, audited access, a Flyway-owned schema and functional tests against real infrastructure.

The main constraints were:

- Document bytes must not bloat Postgres backups or pass through the database connection.
- Documents contain health data; at-rest protection must not depend on the object store being configured
  with server-side encryption.
- The browser must never receive storage credentials, presigned URLs or direct MinIO access.
- A copied object must not become readable under another patient or document id.
- A deleted or unknown document must not become an existence oracle.
- The existing test topology shares one Spring context and one fixed port; a second context for documents
  would conflict with it.

## Consequences

Positive:

- Postgres stays small and fast; metadata queries and pagination do not read document bytes.
- Encryption, key versioning and integrity checking are explicit in code and covered by tests.
- A copied ciphertext fails because the ids and key version are authenticated as AAD.
- The browser only ever talks to the API; downloads are ordinary authenticated responses with
  `no-store`, `nosniff` and an attachment disposition.
- The audit trail includes uploads and downloads, and an audit outage refuses the operation rather than
  doing it without a trace.
- The test suite stays on one Spring context and does not pay for MinIO in unrelated functional tests.

Costs and limits:

- The bucket and the database must be backed up together; either alone is not a complete restore.
- Old key versions must be kept for as long as their documents exist; there is no re-encryption job.
- There is no user-facing delete or retention workflow. Deleting a patient with documents is blocked, and
  the only object deletions are rollback cleanups (a failed cleanup can leave an orphan object).
- A MinIO outage makes document endpoints answer `503` but does not change `/readyz`, which covers only
  readiness and the database.
- The operator must provision the bucket and least-privilege credentials outside the API in staging/prod;
  Terraform does not create MinIO.

## Alternatives rejected

- **Bytes in Postgres (`bytea` or large objects).** Simpler transactions, but database backups and
  connections carry health documents, and the API's database role would need access to the content.
- **Plaintext objects with storage-side encryption only.** Would make at-rest protection depend on bucket
  configuration outside the repository and would not authenticate the object to its row.
- **Presigned URLs or direct browser-to-MinIO access.** Removes the API from the download path and exposes
  storage credentials or bearer URLs to the browser, against the BFF rule that the browser only holds the
  session cookie.
- **One global key without versions.** Rotation would make every existing document unreadable at once.
- **Filename or title as the object key.** Collisions, path handling and renames; the generated
  `{patientId}/{documentId}` key is stable and opaque.
- **An explicit document delete endpoint.** Would require a coordinated object/row delete, a retention
  policy and a decision on audit history; not in the MLink-9 scope. Rollback cleanup is the only deletion.
- **A per-class MinIO container or a second Spring context for document tests.** Conflicts with the shared
  fixed-port functional-test context and starts Docker work for tests that do not use documents. The lazy
  adapter keeps one context and starts MinIO only when needed.
