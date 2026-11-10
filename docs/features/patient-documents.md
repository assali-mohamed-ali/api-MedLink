# Patient documents (MLink-9 / MLink-123/125)

Status: implemented by migration `V14__patient_documents.sql` and the `document/` package. This page is the
reference for the endpoint contracts, storage format, audit rules, limits and tests.

## Purpose and access

Administrative staff attach PDF, PNG or JPEG documents to a patient (a report, a scan, a prescription). The
patient and staff can list and download them. A practitioner has no access to this feature in the current
scope.

The API keeps the document bytes **outside Postgres**, encrypted in an S3-compatible object store (MinIO),
and keeps only searchable metadata in the `patient_documents` table. Keycloak remains the only identity
source; the session cookie and the usual role rules protect every call.

### Access matrix

| Caller | Upload (`POST`) | List (`GET`) | Download (`GET /{documentId}`) |
|---|---|---|---|
| Anonymous / expired session | `401 UNAUTHENTICATED` | `401 UNAUTHENTICATED` | `401 UNAUTHENTICATED` |
| Patient, owner of the path patient | `403 FORBIDDEN` | `200` | `200` |
| Patient, another patient | `403 FORBIDDEN` | `403 FORBIDDEN` | **`404 NOT_FOUND`** |
| Practitioner | `403 FORBIDDEN` | `403 FORBIDDEN` | `403 FORBIDDEN` |
| Administrative staff | `201` (known patient) | `200` (known patient) | `200` (document of that patient) |

The rules are enforced in three places, deliberately:

- `SecurityConfig` route matchers: upload is staff-only; list/download are patient-or-staff.
- `@PreAuthorize` on `DocumentController`: the same role rule at the method.
- `DocumentService`: a patient may list only their own patient id, and a download of another patient's
  document answers exactly like an unknown document (`404`), never `403`, so document ids cannot be probed.

A patient is a row in `patients` (`Patient extends User`, JPA `JOINED`). A Keycloak account in the
`/patients` group that has no `patients` row is not a known patient for upload/list and gets `404` when
staff target it. The functional tests create the profile row explicitly for console-made accounts.

## Endpoint contracts

Base path: `/api/patients/{patientId}/documents`. Every write needs the `X-XSRF-TOKEN` header copied from
the readable `XSRF-TOKEN` cookie; a missing or wrong token is `403 FORBIDDEN` (audited as a refused access).

### Upload

`POST /api/patients/{patientId}/documents` — `multipart/form-data`, staff only.

| Part | Required | Rules |
|---|---|---|
| `title` | yes | trimmed; 1–200 characters after trimming |
| `file` | yes | non-empty; at most **10 MiB (10,485,760 bytes)**; first bytes must be PDF (`%PDF-`), PNG (the 8-byte PNG signature) or JPEG (`FF D8 FF`) |

The client's `Content-Type` header and filename extension are **not trusted**. The API derives the canonical
type from the magic bytes and stores `application/pdf`, `image/png` or `image/jpeg`. The filename is reduced
to its basename (no `/`, `\`, control characters or quotes), capped at 255 characters, and falls back to
`document` when nothing usable remains.

Success: `201 Created`, `Location: /api/patients/{patientId}/documents/{documentId}`, and a `DocumentDto`:

```json
{
  "id": "3f1c0a8e-8d1f-4a38-9d4b-0f0d1a2b3c4d",
  "patientId": "9a7e5d4c-3b2a-4190-8f7e-6d5c4b3a2f10",
  "uploadedBy": "6b5a4c3d-2e1f-4098-a7b6-c5d4e3f2a1b0",
  "title": "Compte rendu",
  "originalFilename": "compte-rendu.pdf",
  "contentType": "application/pdf",
  "sizeBytes": 12345,
  "createdAt": "2026-11-02T10:15:30Z"
}
```

`uploadedBy` is the Keycloak id of the staff caller; it becomes `null` if that account is later deleted.
`sizeBytes` is the plaintext size, not the encrypted object size.

Validation order matters: the patient is checked first (an unknown patient is `404` before the file is
read), then the title and file, then the type, then encryption and storage.

### List

`GET /api/patients/{patientId}/documents?page=0&size=20` — patient owner or staff.

| Query | Default | Bounds |
|---|---|---|
| `page` | `0` | `>= 0` |
| `size` | `20` | `1..50` |

Documents are newest first: `created_at DESC, id DESC`. The response is one page:

```json
{
  "items": [ { "id": "...", "patientId": "...", "uploadedBy": "...", "title": "...",
               "originalFilename": "...", "contentType": "application/pdf",
               "sizeBytes": 12345, "createdAt": "..." } ],
  "page": 0,
  "size": 20,
  "total": 1
}
```

An unknown patient is `404` for staff. A patient asking for another patient id is `403` from the ownership
guard, before any lookup. A bad page/size is `400 VALIDATION_FAILED`.

### Download

`GET /api/patients/{patientId}/documents/{documentId}` — patient owner or staff.

Success is the plaintext bytes with:

| Header | Value |
|---|---|
| `Content-Type` | the canonical type stored in the row (`application/pdf`, `image/png`, `image/jpeg`) |
| `Content-Length` | plaintext length |
| `Cache-Control` | `no-store` |
| `X-Content-Type-Options` | `nosniff` |
| `Content-Disposition` | `attachment` with the original filename; RFC 5987 `filename*=UTF-8''...` for non-ASCII names |

The API never renders the file inline. Before returning anything it loads the encrypted object, decrypts it,
and verifies the stored SHA-256. A missing object is `503 STORAGE_UNAVAILABLE`; a tampered/unreadable object
is `500 INTERNAL_ERROR` and no bytes are sent.

A document id that does not belong to the path patient is `404`; staff cannot fetch a document through
another patient's path. A patient asking for someone else's document is also `404` (not `403`).

## Storage and encryption design

### Metadata only in Postgres

`patient_documents` contains no document bytes and no key material:

| Column | Meaning |
|---|---|
| `id` | document UUID, generated by the API before the insert |
| `patient_id` | owner patient (`ON DELETE RESTRICT`) |
| `uploaded_by` | staff Keycloak id (`ON DELETE SET NULL` when that account is deleted) |
| `title`, `original_filename`, `content_type`, `size_bytes` | display metadata |
| `sha256` | lowercase hex SHA-256 of the plaintext |
| `storage_key` | unique object key in the bucket |
| `key_version` | AES key version used for this document |
| `created_at` | upload time |

The Flyway migration also constrains `content_type` to the three accepted types, `size_bytes` to
`1..10485760`, the SHA-256 format, and adds the `(patient_id, created_at DESC, id DESC)` index used by
the list endpoint.

### Object key layout

The actual key built by `DocumentService.storageKey(...)` is:

```text
{patientId}/{documentId}
```

Both values are the canonical UUID strings, for example
`9a7e5d4c-3b2a-4190-8f7e-6d5c4b3a2f10/3f1c0a8e-8d1f-4a38-9d4b-0f0d1a2b3c4d`. There is **no prefix, no
filename, no extension and no key version in the object key**. The bucket comes from configuration and is
not part of the key. The row's `storage_key` is the source of truth for retrieval.

### Encrypted object format

`AesDocumentCipher` uses AES-256-GCM (`AES/GCM/NoPadding`) with a 128-bit authentication tag and a fresh
12-byte random IV per document. The object value is:

| Bytes | Content |
|---|---|
| `0` | key version, one unsigned byte (`1..255`) |
| `1..12` | 12-byte random IV |
| `13..end` | AES-GCM ciphertext followed by the 16-byte tag |

The associated data (AAD) is the US-ASCII string:

```text
{documentId}|{patientId}|{keyVersion}
```

Because the ids and version are authenticated, a ciphertext copied to another row, another patient or
another key version does not decrypt. On read the ciphertext's first byte must equal the row's
`key_version`; the row chooses the key. A missing key version, a wrong key or a modified payload becomes a
`DocumentIntegrityException` (`500 INTERNAL_ERROR`).

Keys are base64-encoded **32-byte** values under `app.documents.encryption.keys.<version>`. Version `1` is
the base-configuration entry; `app.documents.encryption.active-version` selects the key for new uploads.
Rotation is additive: add a new version, point `active-version` at it, and keep every older key as long as
documents reference it. Existing rows keep their `key_version`; there is no re-encryption endpoint. Removing
a key that old documents still reference makes those documents permanently unreadable.

### Write and read paths

Upload:

1. Check that the patient exists.
2. Validate title, file presence, size and magic bytes; sanitize the filename.
3. Generate the document UUID, compute SHA-256, encrypt the plaintext.
4. Store the encrypted object under the key above.
5. In one database transaction: insert the metadata row and write the upload audit entry.
6. If that transaction rolls back, a transaction synchronization deletes the object. If the object was
   stored but the transaction never registered its cleanup, the catch block deletes it immediately. A failed
   cleanup is logged; it can leave an orphan object but never a row without its bytes.

Download:

1. Apply the ownership rule before the lookup (patient) or allow staff.
2. Read the row by `documentId` **and** `patientId`; a miss is `404`.
3. Load the object, decrypt with the row's key version, verify the AAD.
4. Compare the SHA-256 of the plaintext with the stored value in constant time (`MessageDigest.isEqual`).
5. Audit the successful read, then return the bytes.

### MinIO adapter

`MinioDocumentStorage` uses the S3 API. The client is built at startup **without contacting MinIO**. The
bucket is checked lazily on the first operation:

- `create-bucket=true` creates it when absent (dev/self-hosted convenience).
- `create-bucket=false` treats a missing bucket as `503 STORAGE_UNAVAILABLE` (staging/prod expect the bucket
  to be provisioned outside the API).
- `NoSuchKey`/`NoSuchObject` is recognised on delete/stat; other failures become the generic
  `503 STORAGE_UNAVAILABLE`.
- Objects are written as `application/octet-stream`; no document content is logged.

## Audit behaviour

The audit trail is the shared `audit_entries` table written by `AuditService`.

| Operation | Action / resource / outcome | Transaction behaviour |
|---|---|---|
| Upload | `DOCUMENT_UPLOAD` / `PATIENT_DOCUMENT` / `SUCCESS` | `recordInCurrentTransaction` (`MANDATORY`): the metadata and the audit entry commit together. An audit failure raises `503 AUDIT_UNAVAILABLE`, rolls the metadata back, and deletes the stored object. |
| Download | `DOCUMENT_READ` / `PATIENT_DOCUMENT` / `SUCCESS` | `record` (`REQUIRES_NEW`) after decryption and checksum verification, before the bytes are returned. An audit failure raises `503 AUDIT_UNAVAILABLE` and no document bytes are returned. |
| Refused call | `ACCESS_DENIED` / `REQUEST` / `DENIED` | Written by `JsonAccessDeniedHandler` for a `403`, including a missing CSRF token; the refusal stands even if the audit write fails. |

The audit resource id is the document UUID. The trail never contains the title, filename or document
content. Listing is not audited; only upload, download and refusals are.

## Delete and retention semantics

There is **no document delete endpoint** and no retention/expiry job. A document stays while its patient
exists; its bytes and metadata are effectively immutable through the API.

- **Deleting a patient with documents** is refused by the `patient_documents_patient_id_fkey` foreign key
  (`ON DELETE RESTRICT`). `UserService.delete` turns the constraint violation into
  `409 DOCUMENTS_PRESENT`; the transaction rolls back. The user row, the Keycloak account and the documents
  all remain. Deleting a patient without documents still returns `204`.
- **Deleting a staff author** is allowed: `uploaded_by` is `ON DELETE SET NULL`, so the document stays and its
  `uploadedBy` becomes `null`. The patient is not affected.
- **Rollback cleanup** deletes an object only when its metadata transaction failed. If that delete itself
  fails, the API logs the orphaned key; there is no reconciliation job. A manual bucket/DB comparison is the
  operational fallback.
- Restoring a database without the matching bucket restores metadata whose objects may be missing; back up
  the database and the bucket together.

## Configuration

All document settings live under `app.documents.*`; the exact keys are validated when the Spring context
starts.

| Property | Environment variable (when provided) | Default / expectation |
|---|---|---|
| `app.documents.max-size` | — | `10MB`; must be positive and at most 10 MiB |
| `app.documents.storage.endpoint` | `APP_DOCUMENTS_STORAGE_ENDPOINT` | required; dev `http://localhost:9100`; must start with `https://` when TLS is required |
| `app.documents.storage.access-key` | `APP_DOCUMENTS_STORAGE_ACCESS_KEY` | required by the deployment; MinIO/S3 access key |
| `app.documents.storage.secret-key` | `APP_DOCUMENTS_STORAGE_SECRET_KEY` | required by the deployment; MinIO/S3 secret key |
| `app.documents.storage.bucket` | `APP_DOCUMENTS_STORAGE_BUCKET` | required; non-blank |
| `app.documents.storage.create-bucket` | `APP_DOCUMENTS_STORAGE_CREATE_BUCKET` | `true` in dev; `false` in staging/prod |
| `app.documents.storage.require-tls` | `APP_DOCUMENTS_STORAGE_REQUIRE_TLS` | `false` in dev; `true` in staging/prod |
| `app.documents.encryption.active-version` | `APP_DOCUMENTS_ENCRYPTION_ACTIVE_VERSION` | `1`; must be `1..255` and have a key |
| `app.documents.encryption.keys.<version>` | `APP_DOCUMENTS_ENCRYPTION_KEY` for version 1 | base64 of exactly 32 bytes; every configured version must be valid |
| `spring.servlet.multipart.max-file-size` | — | `10MB` |
| `spring.servlet.multipart.max-request-size` | — | `11MB` (title + multipart boundaries) |
| `spring.servlet.multipart.file-size-threshold` | — | `1MB` (small files stay in memory) |

Startup validation rejects:

- a missing/blank endpoint or bucket;
- a plain `http://` endpoint when `require-tls=true`;
- a missing active version or a missing key for it;
- a key that is not base64 or does not decode to 32 bytes;
- a max size over 10 MiB.

Errors never echo a key value. A bad configuration stops the application instead of starting with documents
that could never be decrypted.

`docker-compose.yml` passes `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` as the API's storage access/secret keys
and reads the endpoint, bucket and flags from `APP_DOCUMENTS_*`. The Terraform path passes the
`APP_DOCUMENTS_*` values directly.

## Deployment notes

- **Dev:** `compose.yaml` runs a pinned MinIO image on the host (`127.0.0.1:9100`, S3 API; console on the
  private network only) with a `minio-data` volume and fixed dev-only credentials. The bucket is created on
  the first upload.
- **Full stack:** `docker-compose.yml` includes the same MinIO service for a self-hosted stack. Staging and
  prod can instead point the API at an external S3-compatible MinIO by setting the endpoint and bucket;
  `create-bucket=false` expects provisioning to own the bucket.
- **Terraform does not provision MinIO.** `terraform/` passes an external store endpoint, bucket,
  credentials and encryption key to the API container; the bucket and its credentials are managed outside
  this repository.
- **TLS / private CA:** staging/prod require an `https://` endpoint. For a private CA, add its certificate to
  the JVM truststore used by the API (for example via `JAVA_TOOL_OPTIONS` pointing at a truststore); never
  disable certificate or hostname verification.
- **Secrets:** storage credentials and `APP_DOCUMENTS_ENCRYPTION_KEY` come from the environment/GitHub
  Environment secret, never from git. Use a different encryption key and a different bucket per environment.
- **Startup and health:** document configuration is validated during context startup. MinIO itself is
  contacted lazily, and `/readyz` includes only `readinessState` and `db`; a MinIO outage therefore does not
  make readiness `DOWN`. Document endpoints answer `503 STORAGE_UNAVAILABLE` until the store is reachable.
  A missing bucket with `create-bucket=false` is also a `503` on the first document operation.
- **Backups and rotation:** back up the bucket and Postgres together. To rotate the encryption key, add a new
  key version, move `active-version`, restart, and keep all old keys referenced by existing rows.

## Failure and error table

All errors use the shared shape `{timestamp, status, error, code, message, details}`.

| Status | Code | Typical cause |
|---|---|---|
| `400` | `VALIDATION_FAILED` | blank/missing title, title over 200, empty file, missing multipart part, invalid page/size or non-numeric query value, malformed request |
| `400` | `UNSUPPORTED_FILE_TYPE` | first bytes are not PDF, PNG or JPEG |
| `401` | `UNAUTHENTICATED` | no session, ended session or 15-minute idle expiry |
| `401` | `SESSION_EXPIRED` | Keycloak refused to renew the account's token |
| `403` | `FORBIDDEN` | wrong role for the route, another patient's list, or missing/wrong `X-XSRF-TOKEN` on a write |
| `404` | `NOT_FOUND` | unknown patient for staff upload/list; unknown document; document not belonging to the path patient; patient downloading another patient's document |
| `409` | `DOCUMENTS_PRESENT` | `DELETE /api/users/{id}` on a patient that still has documents |
| `413` | `FILE_TOO_LARGE` | file over 10 MiB, rejected by the multipart parser or the service |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | upload body is not `multipart/form-data` |
| `500` | `INTERNAL_ERROR` | stored object no longer decrypts, AAD/key version mismatch, SHA-256 mismatch, database failure |
| `503` | `STORAGE_UNAVAILABLE` | MinIO unreachable, missing bucket with `create-bucket=false`, missing object |
| `503` | `AUDIT_UNAVAILABLE` | upload or download audit entry could not be written; the operation is not treated as successful |
| `503` | `SERVICE_UNAVAILABLE` | container-level error path for other unavailable infrastructure |

The `413` can be produced before the controller runs, while the multipart request is parsed;
`ApiErrorController` and `GlobalExceptionHandler` keep the same `FILE_TOO_LARGE` body in that case.

## Test coverage

| Test class | Methods | What it proves |
|---|---|---|
| `functional/PatientDocumentFunctionalTest` | 25 | Full lifecycle over a real API, Keycloak, Postgres and MinIO: upload `201` + `Location` + DTO, magic-byte typing, title/filename rules, exactly 10 MiB accepted and 10 MiB + 1 refused with `413`, unsupported type, `415`, unknown patient `404`, role refusals, anonymous `401`, empty list/defaults, `size` bounds, pagination/newest-first order, exact download bytes and security headers, RFC 5987 filename, non-owner list `403`/download `404`, staff reading any patient, `409 DOCUMENTS_PRESENT`, and a patient without documents still deletable. |
| `functional/PatientDocumentAccessFunctionalTest` | 8 | The access matrix specifically: anonymous, patient, practitioner and staff on upload/list/download, owner access, another patient's list (`403`) and download (`404`). |
| `document/PatientDocumentFailureIntegrationTest` | 5 | Paths a functional test cannot trigger on demand: audit failure on upload rolls back metadata and deletes the object; audit failure on download returns `503` without bytes; a missing object is `503 STORAGE_UNAVAILABLE`; a tampered object is `500 INTERNAL_ERROR`; the audit exception maps to `503 AUDIT_UNAVAILABLE`. It starts its own MinIO so it can delete/overwrite objects. |
| `document/AesDocumentCipherTest` | 9 | AES-GCM round trip; AAD binds the document id, patient id and key version; modified byte, unknown version and wrong key are refused; missing/malformed/short/long keys are refused at startup without echoing the key. |
| `document/DocumentPropertiesTest` | 7 | Startup validation: TLS rule, blank endpoint/bucket, 10 MiB ceiling, missing/active/wrong-sized keys, valid 32-byte base64 key. |
| `service/DocumentKeyStartupTest` | 4 | The application context starts with a valid document key and refuses a missing, malformed or wrong-sized key without echoing its value. |
| `service/UserServiceTest` | 9 | The patient delete path maps only `patient_documents_patient_id_fkey` to `409 DOCUMENTS_PRESENT`, ignores a similarly named unrelated archive FK, and still deletes a patient without documents. |
| `functional/AccessMatrixFunctionalTest` | 3 route lines | Every `/api` route has a line; the three document routes are covered for anonymous, patient, practitioner and staff, including the download `404` for another patient. |

### The one-context lazy-MinIO design

The functional tests run the API on a fixed `DEFINED_PORT` and share one Spring context (and one Keycloak)
across classes. Adding a second `@DynamicPropertySource` context just for documents would try to start a
second API on the same port, so `FunctionalTestBase.DocumentStorageConfig` instead registers a
`@Primary` `LazyMinioStorage` adapter. Its delegate is a real `MinioDocumentStorage` built from
`TestcontainersConfiguration.minioEndpoint()` on the **first** storage operation, under double-checked
locking. A functional test that never touches documents never starts the MinIO container; document tests
share the one container and context. `PatientDocumentFailureIntegrationTest` uses its own fresh MinIO
container because it deliberately deletes and overwrites objects.

The MinIO image is pinned by digest in `TestcontainersConfiguration`; Docker must be running for these tests.

### Commands

```bash
# Document tests only (functional + unit/integration; Docker required)
./mvnw -B test -Dtest='PatientDocument*Test,AesDocumentCipherTest,DocumentPropertiesTest'

# Whole suite, including the functional access matrix
./mvnw -B verify
./mvnw -B test -Dsurefire.runOrder=reversealphabetical
```

The Postman collection has a patient-documents folder with upload/list/download and the practical
`401`/`403`/`404`/`409`/`415` cases. Its multipart request reads
`docs/postman/fixtures/patient-document.pdf`, so run Newman from the repository root; in the Postman app,
reselect that file if the imported path is not resolved.
