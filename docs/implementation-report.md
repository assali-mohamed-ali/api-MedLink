# MedLink backlog implementation report

## Baseline and integration

- API baseline 79e99c5ea8a4565985e00b9193b151f12436c139.
- Dashboard baseline a240f63fbca3f2c4405c4cdc49300198e6cb25e1.
- Claude’s consolidated feature branch is an ancestor of the integration. The previous report remains available in that commit’s history. The exams, staff booking and reassignment scaffold are present in the integrated branch.
- Both repositories use the same feature branch name. The API must be packaged and migrated before serving the new dashboard build. Existing route families and BFF session semantics remain supported.
- The [coverage matrix](backlog-coverage.md) tracks each acceptance statement against the implementation, security controls and tests. The incoming baseline audit classified 20 stories as implemented, six as partial, and six as missing. The release gate counts demonstrated acceptance. UI and API suite success alone is insufficient to mark a story validated.

## Integrated contracts and UI

| Stories | API and contract | Dashboard |
| --- | --- | --- |
| US-03 | POST /api/auth/password/forgot with email and POST /api/auth/password/reset with token and newPassword. The public response is generic for recovery requests. | Recovery request and mailed reset route, linked from login |
| US-04 | GET /api/auth/consent returns version, text and required. Registration accepts consentVersion and records its timestamp. Production registration is held pending policy approval. | Policy loaded from the API, explicit unchecked acknowledgement |
| US-08 | AppointmentConfirmation uses the committed appointment to send confirmation for patient booking, staff booking, and patient rescheduling. | Existing confirmation and new staff receipt |
| US-13, US-25 | GET /api/results/me, POST and GET /api/patients/{id}/results. Publisher posts title, examType, examDate, content and optional existing documentId. Patient sees publication time, author and new indicator. | Patient results and attachment download, practitioner review and publication inside the patient record |
| US-15 | GET and PUT /api/patients/me return phone and address. The request allowlist matches those fields. | Editable contact details under patient profile |
| US-16 | GET /api/patients/me/export downloads JSON. GET/POST/DELETE /api/patients/me/deletion-request manage patient requests. Staff reviews via GET /api/admin/deletion-requests and POST /api/admin/deletion-requests/{patientId}/decision. | Patient profile export and request controls, administrative review page |
| US-21 | GET and POST /api/practitioner/absences, GET /api/practitioner/absences/{absenceId}/appointments, POST /api/practitioner/appointments/{appointmentId}/absence-decision. | Absence periods and explicit appointment decisions |
| US-22 | GET /api/practitioner/patients?q= returns bounded, relationship-scoped summaries. | Practitioner patient directory |
| US-29 | PUT /api/admin/practitioners/{practitionerId}/clinics accepts clinicIds and returns updated assignments. Existing GET /api/admin/practitioners and profile choices provide current values. | Staff clinic-assignment editor and review step |
| US-30 | GET /api/admin/slots and /api/admin/slots/filters share slot search with the patient contract. POST /api/admin/appointments accepts patientId and slotId. | Staff patient search, slot filters, confirmation and receipt |

Controllers use existing Springdoc scanning for OpenAPI/Scalar. The maintained Postman collection includes a public identity folder. Authenticated backlog contracts are documented above and still need Postman scenarios.

## Persistence and authorization

Migrations V24–V29 add registration consent, reset tokens, deletion requests, absences, encrypted examination results and a reassignment scaffold. Apply in order with Flyway. V29 adds persistence only. Restore a synthetic V23 snapshot before testing an existing-data upgrade.

The original BFF still keeps Keycloak sessions and tokens server-side with role guards, CSRF, and HttpOnly cookies. Administrative booking uses the patient for ownership, the staff identity for booking attribution and audit, and checks the patient’s verified active account. Clinic assignments, absence declaration and slot opening serialize through the practitioner schedule lock. Deletion submission and decisions serialize through the patient lock. Personal export and deletion initiation require an interactive login within ten minutes. Export sends Cache-Control no-store.

The exam-result workflow reuses encryption for the medical title, type and contents. Documents stay in the existing encrypted object storage and the patient download route. The [security review](security/backlog-integration.md) records the decisions and gaps.

## Acceptance evidence and remaining work

See the [manual acceptance instructions](manual-testing.md). The complete release gate remains open for the reasons recorded in the matrix, including the approved consent wording, verified identity-email change and notification preferences, exam publication notification and dedicated attachment upload, personal data export completeness and account closure, and two-party reassignment before clinic removal. The incoming retention proposal is recorded in the project decision document and awaits review. The workflow preserves patient records and audit history.

## Verification commands and evidence

| Check | Command or evidence | Result |
| --- | --- | --- |
| Dashboard baseline | npm test at a240f63 | Exit 0, 543 passed |
| Dashboard implementation | npm test | Exit 0, 559 passed in 78 files, 0 failed, 0 skipped |
| Dashboard quality | npm run lint, npm run build, npm run check:budget, npm run check:contrast | Exit 0 for each. Two pre-existing React lint warnings in the documents feature. JavaScript 106.8 kB gzip and fonts 48.4 kB. All 26 contrast checks passed. |
| Dependency advisory check | npm audit --omit=dev --json | Exit 0, no production dependency advisories |
| Secret scan | Gitleaks git /repo --redact --log-opts master..HEAD in each repository | No leaks found in inspected feature commits |
| Compose | docker compose -f compose.yaml config --quiet and docker compose --env-file .env.example -f docker-compose.yml config --quiet | Exit 0 |
| SCA policy | bash scripts/ci/sca-policy.test.sh | Exit 0, 18 policy test cases passed |
| Migration upgrade | Isolated PostgreSQL, Flyway target 23, synthetic patient insertion, migrate and validate to 29 | Six migrations applied. Existing patient preserved, consent history nullable for pre-existing rows. |
| Public identity collection | Newman 6.2.1, folder 14, against local standalone API | Exit 0, four requests and assertions passed |
| Browser patient smoke | Chromium against the production dashboard build and local API, test Keycloak and Mailpit | Registration rejected without consent; consent/activation, login, refresh, contact persistence, export, deletion request and withdrawal, recovery and revoked session passed |

The first combined API verification found profile whitespace validation, PostgreSQL timestamp precision, missing matrix coverage and a configuration test failure. A concurrent compilation invalidated that run’s classpath during alert tests. A separate clean run executed 1,216 tests with three matrix failures. The two staff slot endpoints still had a patient-only method check and the matrix expected an unrelated practitioner to reach exam validation. The feature branch corrects the route guards and the expectation for a random unrelated patient ID. Logs are retained under /tmp/medlink-backlog-*.log.

## AI disclosure

Any future pull request must declare AI assistance and leave the human review checkbox for the reviewer.
