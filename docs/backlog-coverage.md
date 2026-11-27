# Backlog coverage: US-01 to US-32

Phase A of the "complete functional implementation" request: a traceability matrix built by reading the code of both
repositories. It is an audit, not a test run: no test was executed to produce it.

- Baseline: `api-MedLink` master `79e99c5`, `medlink-dashboard` master `a240f63` (2026-11-27).
- Source of the stories: `MedLink_Product_Backlog_Roadmap.xlsx`, worksheet `Détail PBIs`. That sheet holds the
  title, actor, release, priority and estimate of each story, but **no acceptance criteria**; criteria still have to
  be taken from the SRS and the YouTrack stories.
- Statuses: `NOT_IMPLEMENTED`, `PARTIALLY_IMPLEMENTED`, `IMPLEMENTED_NOT_VALIDATED`, `VALIDATED`, `BLOCKED`.
  A story is `VALIDATED` only when its acceptance criteria have been checked one by one. None is marked so here:
  "IMPLEMENTED_NOT_VALIDATED" means the code exists on both sides and the project's own test suite covers it
  (see the report), but this audit did not re-run it or compare it with the criteria.

Initial functional coverage by this definition: **0 / 32 validated**; **20 implemented**, **6 partial**,
**6 not implemented**.

## Matrix

| US | Title | Actor | Rel. | Prio | Pts | Backend | Frontend | Status | Remaining work |
|----|-------|-------|------|------|-----|---------|----------|--------|----------------|
| 01 | Création de compte patient | Patient | 1 | Haute | 5 | `AuthBffController` `POST /api/auth/register`, `/verify-email`, `/verify-email/resend` (same answer whether the account exists) | `SignupPage`, `VerifyEmailPage` | IMPLEMENTED_NOT_VALIDATED | Check criteria; consent is missing (US-04) |
| 02 | Connexion patient | Patient | 1 | Haute | 3 | `LoginController`, `SessionController` `/api/auth/me`, `LogoutController`; HttpOnly session + CSRF | `LoginPage`, auth provider | IMPLEMENTED_NOT_VALIDATED | Check criteria |
| 03 | Réinitialisation du mot de passe | Patient | 1 | Haute | 3 | Only the logged-in change `POST /api/auth/password` and the staff-triggered `force-password-reset`. **No "forgot my password" request or token** | `OwnPasswordPage`, `PasswordPage` (change only) | NOT_IMPLEMENTED | Public recovery request, single-use expiring token, reset form, generic responses, session revocation |
| 04 | Consentement à l'inscription | Patient | 2 | Moyenne | 3 | Nothing: no consent field, table or check anywhere (`grep -i consent` is empty) | `SignupPage` has no consent box | NOT_IMPLEMENTED | Needs approved legal text and policy version from the team; then a migration, a required field enforced by the API, and the form box |
| 05 | Recherche d'un praticien | Patient | 1 | Moyenne | 3 | `GET /api/slots/filters` (clinics, specialties) and the `practitionerId` filter of `GET /api/slots`; no dedicated practitioner list endpoint | `SlotSearchPage` | PARTIALLY_IMPLEMENTED | Confirm against the criteria whether a practitioner list or name search is required |
| 06 | Recherche d'un créneau | Patient | 1 | Haute | 5 | `SlotController.search` (dates, clinic, specialty, practitioner, paging, 31-day limit) | `SlotSearchPage` | IMPLEMENTED_NOT_VALIDATED | Check criteria |
| 07 | Réservation d'un rendez-vous | Patient | 1 | Haute | 5 | `AppointmentController` `POST /api/appointments`; concurrency covered by tests (`SLOT_TAKEN`, `PATIENT_BUSY` per the report) | `SlotSearchPage` | IMPLEMENTED_NOT_VALIDATED | Check criteria |
| 08 | Confirmation de réservation | Patient | 2 | Moyenne | 3 | `MailService` has mails for cancel, move, reminder; **no "booking confirmed" mail** (screen confirmation only) | confirmation dialog in `SlotSearchPage` | PARTIALLY_IMPLEMENTED | Confirm whether an email is required; if yes add it with idempotent sending |
| 09 | Annulation d'un rendez-vous | Patient | 2 | Haute | 3 | `POST /api/appointments/{id}/cancel`, alert to the practitioner | `MyAppointmentsPage` | IMPLEMENTED_NOT_VALIDATED | Verify the deadline rule (12 h) against the SRS |
| 10 | Report d'un rendez-vous | Patient | 2 | Haute | 5 | `POST /api/appointments/{id}/reschedule` | `MyAppointmentsPage` | IMPLEMENTED_NOT_VALIDATED | Check criteria |
| 11 | Historique des rendez-vous | Patient | 2 | Moyenne | 3 | `GET /api/appointments/me` | `MyAppointmentsPage` | IMPLEMENTED_NOT_VALIDATED | Check filtering and paging criteria |
| 12 | Rappel avant le rendez-vous | Patient | 2 | Haute | 5 | `AppointmentReminderJob` (`reminder-hours-before`, 24 h default, skips cancelled, retries, one reminder per appointment) | none needed | IMPLEMENTED_NOT_VALIDATED | Check with US-48 |
| 13 | Consultation des résultats d'examens | Patient | 2 | Haute | 5 | Only `GET /api/patients/{id}/documents` (documents uploaded by staff). **No examination-result entity** (title, type, date, publisher, publication state) | `PatientDocumentsPage` ("Mes documents") | PARTIALLY_IMPLEMENTED | Result domain, publication state, patient list/detail; to build together with US-25 |
| 14 | Téléchargement d'un document médical | Patient | 2 | Moyenne | 3 | `GET /api/patients/{id}/documents/{documentId}`; owner or staff; encrypted storage | `PatientDocumentsPage` | IMPLEMENTED_NOT_VALIDATED | Check headers (Content-Disposition, cache-control) |
| 15 | Gestion du profil patient | Patient | 2 | Moyenne | 3 | No self-service update endpoint. Only staff `PUT /api/admin/users/{id}/name` | `PatientProfilePage` is read-only (no form) | PARTIALLY_IMPLEMENTED | `GET/PUT /api/patients/me` with an allowlist (first name, last name, phone, address), audit, form |
| 16 | Export et suppression de mes données | Patient | 3 | Moyenne | 5 | Nothing | Nothing | NOT_IMPLEMENTED | Needs a retention policy decision before any deletion; export can be built first |
| 17 | Connexion praticien avec MFA | Praticien | 1 | Très haute | 5 | `MfaController` `/api/auth/mfa/setup`, `/verify`; TOTP secret AES-256-GCM | `MfaPage`, `ConfigureCodePage` | IMPLEMENTED_NOT_VALIDATED | Check criteria |
| 18 | Définition des disponibilités | Praticien | 1 | Haute | 5 | `PractitionerAgendaController` `POST /api/practitioner/slots` | `AgendaPage` | IMPLEMENTED_NOT_VALIDATED | Check criteria |
| 19 | Agenda multi-sites | Praticien | 2 | Haute | 8 | `GET /api/practitioner/agenda` | `AgendaPage` | IMPLEMENTED_NOT_VALIDATED | Check criteria |
| 20 | Alerte d'annulation de rendez-vous | Praticien | 2 | Moyenne | 3 | `PractitionerAlertController` `GET /api/practitioner/alerts`, `POST /{id}/read`; V17 unique constraint | alerts feature | IMPLEMENTED_NOT_VALIDATED | Check criteria |
| 21 | Blocage de créneaux et absences | Praticien | 3 | Basse | 3 | `PATCH /api/practitioner/slots/{id}` closes or reopens one slot. **No absence period** (`grep -i absence` is empty) | `AgendaPage` (slot only) | PARTIALLY_IMPLEMENTED | Absence entity and endpoints, conflict handling for booked appointments (no silent cancellation), agenda display |
| 22 | Recherche parmi mes patients | Praticien | 2 | Moyenne | 3 | None: only `GET /api/patients/{id}/record` by id | "Patients" on the practitioner home has no link (placeholder); `paths.practitioner.patients` exists but no page | NOT_IMPLEMENTED | Needs the patient-attachment rule from the SRS (record access is "has an appointment with the patient") |
| 23 | Accès au dossier du patient reçu | Praticien | 2 | Haute | 8 | `PatientRecordController` `GET /api/patients/{id}/record` (practitioner role; relationship rule in the service) | `PatientRecordPage` | IMPLEMENTED_NOT_VALIDATED | Check criteria |
| 24 | Rédaction d'un compte-rendu | Praticien | 2 | Haute | 5 | `ConsultationReportController` `POST /api/patients/{id}/reports`; content encrypted | `PatientRecordPage` (reports) | IMPLEMENTED_NOT_VALIDATED | Check immutability rule |
| 25 | Publication d'un résultat d'examen | Praticien | 2 | Haute | 5 | Document upload `POST /api/patients/{id}/documents` is **staff only** (`SecurityConfig`); no practitioner publication | none | NOT_IMPLEMENTED | Result domain with practitioner scope (see US-13) |
| 26 | Historique des comptes-rendus | Praticien | 3 | Moyenne | 3 | `GET /api/patients/{id}/reports`; patient side `GET /api/reports/me` | `PatientRecordPage`, `MyReportsPage` | IMPLEMENTED_NOT_VALIDATED | Check paging |
| 27 | Création de comptes utilisateurs | Personnel | 1 | Haute | 5 | `AdminUserController` `POST /api/admin/users`, `GET /api/admin/profile-choices` (practitioner needs a specialty and clinics) | `AccountsPage` | IMPLEMENTED_NOT_VALIDATED | Check role limits |
| 28 | Modification et désactivation de comptes | Personnel | 2 | Moyenne | 3 | `PUT /{id}/role`, `PATCH /{id}/status`, `PUT /{id}/name`, `POST /{id}/force-password-reset` | `AccountsPage` | IMPLEMENTED_NOT_VALIDATED | Check self-disable and last-admin rules |
| 29 | Affectation des praticiens aux cliniques | Personnel | 2 | Moyenne | 5 | Clinics are set only when creating the account or changing its role (`changeRole(..., clinicIds)`); no endpoint to add or remove one clinic | `AccountsPage` (at creation) | PARTIALLY_IMPLEMENTED | Dedicated assignment endpoints with a check of future slots and appointments |
| 30 | Rendez-vous pris pour un patient | Personnel | 2 | Moyenne | 5 | Booking routes are patient-only (`SecurityConfig`: `/api/appointments/**` needs PATIENT) | none | NOT_IMPLEMENTED | Staff booking operation reusing the booking service, patient search, audit of the staff actor |
| 31 | Supervision des trois cliniques | Personnel | 3 | Moyenne | 5 | `ClinicActivityController` (`/api/admin/practitioners`, `/clinics`, `/clinics/{id}/activity`, `/agenda`), read only | `ClinicActivityPage` | IMPLEMENTED_NOT_VALIDATED | Check criteria |
| 32 | Export de statistiques anonymisées | Personnel | 3 | Moyenne | 5 | `StatsExportController` `GET /api/admin/stats/export`: k-anonymity (5 distinct patients per group), 422 `ANONYMISATION_IMPOSSIBLE`, period ≤ 366 days, audited | `StatsExportPage` | IMPLEMENTED_NOT_VALIDATED | Check CSV formula injection protection |

Counts: IMPLEMENTED_NOT_VALIDATED 20 (US-01, 02, 06, 07, 09, 10, 11, 12, 14, 17, 18, 19, 20, 23, 24, 26, 27, 28, 31, 32),
PARTIALLY_IMPLEMENTED 6 (US-05, 08, 13, 15, 21, 29), NOT_IMPLEMENTED 6 (US-03, 04, 16, 22, 25, 30).

## What is not covered by this audit

- GitHub issues, open pull requests and YouTrack were not read: a story marked missing here may be in progress on
  an epic branch.
- No test, build or scan was run for this document (Docker-based verification takes about 20 minutes per run and
  the frontend repository could not be pushed to from this session).
- Cross-cutting stories (US-33 to US-48) were not individually assessed.

## Product decisions needed before the missing stories can be built

1. **US-04 consent:** the approved wording and the policy version to record.
2. **US-16 deletion:** the retention policy for medical data (the request says to block irreversible deletion
   until it exists). The export part does not depend on it.
3. **US-22:** the rule that attaches a patient to a practitioner (today: an appointment together).
4. **US-21:** what happens to booked appointments when an absence is declared.
5. **US-29:** what happens to future appointments when a clinic is removed from a practitioner.
6. **US-08 / US-05:** whether a confirmation email and a practitioner list are required by the criteria.
