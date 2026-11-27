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
| US-13, US-25 | GET /api/results/me, POST and GET /api/patients/{id}/results. Lists use page (default 0), size (default 20, maximum 50) and return items, page, size, total, unseen. Publication accepts optional documentId and requestId; 201 new, 200 replay, 409 REQUEST_ID_REUSED for incompatible reuse. Only displayed patient results become seen. | Paged patient results and unseen count, practitioner attachment selection/upload and publication inside the patient record |
| US-15 | GET and PUT /api/patients/me return phone and address. The request allowlist matches those fields. | Editable contact details under patient profile |
| US-16 | GET /api/patients/me/export downloads JSON. GET/POST/DELETE /api/patients/me/deletion-request manage patient requests. Staff reviews via GET /api/admin/deletion-requests and POST /api/admin/deletion-requests/{patientId}/decision. | Patient profile export and request controls, administrative review page |
| US-21 | GET and POST /api/practitioner/absences, GET /api/practitioner/absences/{absenceId}/appointments, POST /api/practitioner/appointments/{appointmentId}/absence-decision. | Absence periods and explicit appointment decisions |
| US-22 | GET /api/practitioner/patients?q=&page=&size= returns items, page, size, total; search by name, full name or record number with the record's relationship rule. | Paged practitioner patient directory and record number |
| US-29 | PUT /api/admin/practitioners/{practitionerId}/clinics accepts clinicIds and returns updated assignments. Existing GET /api/admin/practitioners and profile choices provide current values. | Staff clinic-assignment editor and review step |
| US-30 | GET /api/admin/slots and /api/admin/slots/filters share slot search with the patient contract. POST /api/admin/appointments accepts patientId and slotId. | Staff patient search, slot filters, confirmation and receipt |

Controllers use existing Springdoc scanning for OpenAPI/Scalar. The maintained Postman collection includes a public identity folder. Authenticated backlog contracts are documented above and still need Postman scenarios.

## Persistence and authorization

Migrations V24–V31 add registration consent, reset tokens, deletion requests, absences, encrypted examination results, a reassignment scaffold (V29), notification preferences and account closure (V30), and record numbers and result publication tracking (V31). Apply in order with Flyway. V29 adds persistence only. Restore a synthetic V23 snapshot before testing an existing-data upgrade.

The original BFF still keeps Keycloak sessions and tokens server-side with role guards, CSRF, and HttpOnly cookies. Administrative booking uses the patient for ownership, the staff identity for booking attribution and audit, and checks the patient’s verified active account. Clinic assignments, absence declaration and slot opening serialize through the practitioner schedule lock. Deletion submission and decisions serialize through the patient lock. Personal export and deletion initiation require an interactive login within ten minutes. Export sends Cache-Control no-store.

The exam-result workflow reuses encryption for the medical title, type and contents. Documents stay in the existing encrypted object storage and the patient download route. The [security review](security/backlog-integration.md) records the decisions and gaps.

## Acceptance evidence and remaining work

See the [manual acceptance instructions](manual-testing.md). The complete release gate remains open for the reasons recorded in the matrix, including approved consent wording, verified identity-email change, retention approval before anonymisation/purge, completed-versus-started consultation policy, overlapping-export protection, browser acceptance of V30/V31, and two-party reassignment before clinic removal. The incoming retention proposal is recorded in the project decision document and awaits review. The workflow preserves patient records and audit history.

## Verification commands and evidence

### Batch 2 handoff (27 November 2026)

- API head under review: `0437d262d433e6bd9186df5d8c29f07af7413906`; dashboard `bcf88d47192a0610f0af545b05e6eb3f262a8bd1`.
- [Dashboard CI](https://github.com/assali-mohamed-ali/medlink-dashboard/actions/runs/46077144925): npm ci, npm test (581 passed in 84 files, 0 failed, 0 skipped), npm run lint (2 warnings, 0 errors), npm run build all completed successfully (exit 0 per CI step). Separate budget and contrast commands were not confirmed on this head.
- [API CI](https://github.com/assali-mohamed-ali/api-MedLink/actions/runs/46077036424) and [CD](https://github.com/assali-mohamed-ali/api-MedLink/actions/runs/46077036299) were still in progress at inspection. CodeQL, secrets, dependency check, ZAP and container scans reported success. Dependency Review skipped. AI disclosure requires actual human acknowledgement; leave it unchecked until human review.
- Local clone attempts returned exit 128 (GitHub DNS unavailable); Docker is unavailable. Neither Maven order, local frontend checks, Newman, Flyway fresh/upgrade to V31 nor Chromium was executed here. CI is used for candidate validation by explicit request; no new story is marked VALIDATED.
- Earlier handoff reported targeted V30/V31 tests; those are historical reports, not independently rerun in this environment.

### Historical verification (before V30/V31)

| Check | Command or evidence | Result |
| --- | --- | --- |
| API clean verification | ./mvnw -o -B clean verify | Exit 0, 1,216 passed, 0 failed, 0 skipped. Surefire ended an idle fork after the tests had reported. |
| Dashboard clean dependency install | npm ci in a fresh scratch directory with the committed lockfile | Exit 0, 172 installed packages |
| Dashboard baseline | npm test at a240f63 | Exit 0, 543 passed |
| Dashboard implementation | npm test | Exit 0, 559 passed in 78 files, 0 failed, 0 skipped |
| Dashboard quality | npm run lint, npm run build, npm run check:budget, npm run check:contrast | Exit 0 for each. Two pre-existing React lint warnings in the documents feature. JavaScript 106.8 kB gzip and fonts 48.4 kB. All 26 contrast checks passed. |
| Dependency advisory check | npm audit --audit-level=high --json and npm audit --omit=dev --json | Exit 0 for both, no advisories |
| Secret scan | Gitleaks git /repo --redact --log-opts master..HEAD in each repository | No leaks found in inspected feature commits |
| Compose | docker compose -f compose.yaml config --quiet and docker compose --env-file .env.example -f docker-compose.yml config --quiet | Exit 0 |
| SCA policy | bash scripts/ci/sca-policy.test.sh | Exit 0, 18 policy test cases passed |
| Migration upgrade | Isolated PostgreSQL, Flyway target 23, synthetic patient insertion, migrate and validate to 29 | Six migrations applied. Existing patient preserved, consent history nullable for pre-existing rows. |
| Cross-role Chromium smoke | Local production build and API with real Keycloak, Mailpit, PostgreSQL and MinIO | Staff MFA and booking, patient history, staff audit actor, practitioner MFA and agenda, exam publication, patient attachment download passed |
| Reverse-order Maven | ./mvnw -o -B test -Dsurefire.runOrder=reversealphabetical | Started after clean verification. See /tmp/medlink-backlog-reverse.log for the result. |
| Backend SCA | OWASP Dependency-Check with the repository CVSS threshold | Offline execution requires online mode. The online run timed out while resolving scanner dependencies. |
| OWASP ZAP | Passive baseline image requested | The image pull was still pending at handoff. No scan result recorded. |
| Public identity collection | Newman 6.2.1, folder 14, against local standalone API | Exit 0, four requests and assertions passed |
| Browser patient smoke | Chromium against the production dashboard build and local API, test Keycloak and Mailpit | Registration rejected without consent; consent/activation, login, refresh, contact persistence, export, deletion request and withdrawal, recovery and revoked session passed |

The first combined API verification found profile whitespace validation, PostgreSQL timestamp precision, missing matrix coverage and a configuration test failure. A concurrent compilation invalidated that run’s classpath during alert tests. A separate clean run executed 1,216 tests with three matrix failures. The two staff slot endpoints still had a patient-only method check and the matrix expected an unrelated practitioner to reach exam validation. The feature branch corrects the route guards and the expectation for a random unrelated patient ID. Logs are retained under /tmp/medlink-backlog-*.log.

## Git

- Consolidated source for the API was d47deea8fe50d611f65ee2386232a5a3ece2ecb2.
- Dashboard integration is 613bc586bc6177a1b6d3510103fa372de6f98b33 on feat/backlog-us01-us32. The newer dashboard `bcf88d4` requires API `0437d26` or later with V31 and paged responses.
- The feature branches are published in API PR #167 and dashboard PR #47. Production release remains gated by the coverage matrix.

## AI disclosure

Any future pull request must declare AI assistance and leave the human review checkbox for the reviewer.
