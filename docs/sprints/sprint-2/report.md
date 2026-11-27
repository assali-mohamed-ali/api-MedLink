# Sprint 2 report

Updated by every sub-sprint PR. Newest information first inside each section.

## YouTrack gap closure, 2026-11-27

| Ticket | Working-tree implementation |
|---|---|
| MLink-17 and MLink-33 | `GET /api/admin/audit-entries?patientId=…` combines the patient filter with the existing actor, action and period filters. The dashboard can find the patient by name or email. Lists are recorded as `DOCUMENT_LIST` before results are returned. Migration V23 indexes the resource ID and timestamp. |
| MLink-35 | Staff can list clinics and request `GET /api/admin/clinics/{clinicId}/activity?from=&to=` for counts of booked and cancelled appointments. Counts use slot starts in the half-open period. Each dashboard clinic card loads and retries independently above the practitioner agenda. |
| MLink-20 | Staff can change first and last names via the account dialog and `PUT /api/admin/users/{id}/name`. The API updates Keycloak and the local patient or practitioner profile where present. The API records attempts and outcomes as `ACCOUNT_UPDATED`. |
| MLink-24 and MLink-39 | The deploy renderer verifies that the required document encryption key is read from the environment's Vault API bundle. Audit-based security email can be activated with `APP_SECURITY_ALERTS_RECIPIENT`. |
| MLink-34 and MLink-175 | The repository contains the Caddy/Coraza build, the Prometheus/Grafana/Alertmanager stack, node exporter configuration and operational instructions. |

### Role-based access verification

The local API matrix passed all 189 checks, covering every API route across
anonymous, patient, practitioner and administrative staff sessions. The
functional suites additionally exercised patient document ownership, patient
audit filtering and appointment counts scoped by clinic and period.

| Area | Anonymous | Patient | Practitioner | Administrative staff |
|---|---|---|---|---|
| Patient documents | 401 | Own listing and downloads permitted. Other patients' documents and uploads refused. | 403 | Listing, upload and download permitted. |
| Audit journal and patient filter | 401 | 403 | 403 | 200 with filtered entries. |
| Clinic list and activity | 401 | 403 | 403 | 200 for valid requests. Invalid periods return 400 and unknown clinics return 404. |
| Account name | 401 | 403 | 403 | Staff can edit first and last names. The login email is preserved. |

The dashboard has regression tests for patient selection in the audit journal,
saving account names and retrying a failed clinic card independently. The
Sprint 2 Postman collection includes requests for clinic counts, account name
editing and patient audit filtering. The updated collection passed JSON
validation and awaits a Newman run.

### Local verification

- API Maven `verify` in reverse alphabetical test order passed all 1,076 tests.
- A focused follow-up run verified that a name edit reconciles Keycloak and
  the local patient profile while preserving the login email.
- Dashboard lint and production build passed. All 511 dashboard tests passed.
- The dashboard's first-visit JavaScript budget passed at 109.3 kB gzip
  against a 110 kB limit.
- The Vault deploy renderer and the Prometheus and Alertmanager config tests
  passed with fixture credentials and targets.
- The pinned Caddy and Coraza image built and validated the local WAF configuration.

### Public staging performance and layout

Lighthouse 13.6 lab measurements on the deployed staging build, taken on
2026-11-27. The acceptance target is Largest Contentful Paint under two seconds.
The mobile homepage measurement was repeated after the first trace failed.

| Page | Mobile LCP | Desktop LCP | Mobile / desktop performance score |
|---|---:|---:|---:|
| Home | 1.18 s | 0.71 s | 96 / 96 |
| Sign in | 1.81 s | 1.29 s | 93 / 84 |
| Sign up | 1.74 s | 1.55 s | 88 / 87 |

All three pages stayed within the viewport width at 360, 768 and 1440 CSS
pixels. These measurements cover the public routes on the currently deployed
build. [Staging dashboard](https://staging.med-l.ink/)

Staging verification remains open for WAF behavior, private scrape targets,
live Vault access, SMTP delivery and authenticated browser QA. The changes are
local to the integration worktrees. YouTrack rollout status depends on
deployed evidence.

## MLink-26 — Agenda multi-sites (back-end)

**Tickets:** MLink-133, 134, 135, 136, 137, 138, 141. Front (MLink-139, 140) in `web-MedLink`.

**Routes** (class-level `@PreAuthorize(PRACTITIONER)`; the practitioner is always the caller):

| Route | Answer |
|---|---|
| `GET /api/practitioner/agenda?from=&to=` | `{clinics, items}`; period up to 31 days; past slots included; patient names only |
| `POST /api/practitioner/slots` | 201 + the slot; 409 `SLOT_OVERLAP`; 400 `VALIDATION_FAILED` |
| `PATCH /api/practitioner/slots/{id}` | `{"status":"OPEN"\|"CLOSED"}`; 409 `SLOT_BOOKED`, `SLOT_IN_PAST`; 403 if not the caller's slot |

**Decisions**
- V15 inserts the three reference clinics (no endpoint creates a clinic) and adds the `CLOSED` slot status.
- The overlap is enforced by the database (`slots_no_overlap`); the application check only names the slot in the way, and a lost race is translated to the same 409 (never a 500).
- A closed slot keeps its time range: reopening it frees the time (decision D4, to confirm).
- Closing is one conditional `UPDATE` (free and no active appointment): a concurrent booking cannot end on a closed slot. The booking (MLink-102, merged with #87) locks the slot (`SELECT … FOR UPDATE`) and refuses any slot that is not `FREE`, so a closed slot answers 409 `SLOT_TAKEN`; in a race, Postgres's row lock makes one side see the other's status.
- Spring converts query parameters and the JSON body before `@PreAuthorize` runs, so a patient sending `from=not-a-date` got 400 instead of 403 (code owner's review on #116). **`SecurityConfig` now limits `/api/practitioner/**` to `PRACTITIONER`** in the filter chain, before any conversion (shared file, changed at the code owner's request); the class-level `@PreAuthorize` stays as a second check. Nothing is `@Valid` or required either: a patient or staff member gets 403, never 400, whatever they send.
- Two overlapping openings at once: the exclusion constraint can make each insert wait for the other, and Postgres aborts one with a deadlock (40P01), which reached the client as 500. `AgendaService.open` retries once after a `PessimisticLockingFailureException`: the overlap check or the constraint then answers 409 `SLOT_OVERLAP`; a second deadlock also answers 409. Found while revalidating the review fix.
- The agenda is not audited per read (it is not the medical record); slot changes are (`SLOT_OPENED`, `SLOT_CLOSED`); refusals are (`ACCESS_DENIED`).
- Duration 10 min to 8 h and horizon 180 days are assumptions (constants in `AgendaService`), not in the cahier des charges.

**Proofs**
- Repository queries: `SlotRepositoryAgendaTest` (9 tests) with `AppointmentSchemaTest`, `AppointmentDomainRepositoryTest` and `SlotSearchFunctionalTest` still green (39 tests).
- Access matrix: 3 new lines, 73 cases green.
- `AgendaFunctionalTest`: 21 tests green, including `aClosedSlotCannotBeBookedEvenByItsIdAndNoAppointmentIsCreated` (added after the merge of `master`): checked once that it turns red when the booking refuses only `BOOKED` slots.
- Postman: new folder 9 "Practitioner agenda (MLink-26)", 25 requests: anonymous 401 and patient 403 on every route (403, never 400, even without dates or body); a disposable practitioner through the code step gets 400 without dates, 409 `PRACTITIONER_PROFILE_MISSING` and 403 on a slot that is not theirs. Folder alone: 26 requests, 41 assertions, 0 failures. Whole collection, `npx newman@6.2.1 run`: **158 requests, 355 assertions, 0 failures**, twice in a row. The full flow (three clinics, overlap, close, reopen) needs a practitioner profile, which no endpoint creates yet (SS5): it stays proven by `AgendaFunctionalTest`.
- Review on #116 (2026-11-10): `patientsAndStaffGet403EvenWithMalformedDatesOrJson` (malformed dates, malformed JSON, an id that is not a UUID, on the three routes, for a patient and a staff member) committed red first: `expected: 403 but was: 400`; green with the `SecurityConfig` rule. `twoSimultaneousOverlappingOpeningsLeaveExactlyOneSlotAndNoServerError` was red 4 runs out of 5 (201 + 500, SQLSTATE 40P01), green 10 runs out of 10 with the retry. Full suite `./mvnw -B verify`: **519 tests, 0 failures**; reverse class order: 519 tests, 0 failures. Postman folder 9 on the rebuilt dev stack: 26 requests, 41 assertions, 0 failures.
- Full suite `./mvnw -B verify`: 476 tests, 0 failures; reverse class order (`-Dsurefire.runOrder=reversealphabetical`): 476 tests, 0 failures.
- After merging `master` (2026-11-10, booking and slot search index included): `./mvnw -B verify` 517 tests, 0 failures; reverse class order 517 tests, 0 failures (before the closed-slot booking test was added).
- `pre-commit run --all-files`: all 14 hooks passed (gitleaks, checkov, detect-private-key included).
- One isolated failure of `QrCodesTest` (MLink-62) was seen once in a full run; not reproduced in 10 separate runs nor in the next full runs; unrelated to this PR.

**Follow-ups:** `AuditService.recordInCurrentTransaction` (MLink-114) to write the audit in the business transaction; `SlotRepository` is shared with MLink-98; the enum conflict with MLink-114 is to be resolved at rebase (keep all values).

## Status

| Sub-sprint | Scope | State | PR | Date |
|---|---|---|---|---|
| 0 | Sprint plan, backend-only design, YouTrack re-mapped | Done | docs/sprint-2-plan | 2026-10-14 |
| 1 | Registration and email verification (endpoints 1–4) | Done; review fixes landing (`fix/land-pr-39`) | [#32](https://github.com/assali-mohamed-ali/api-MedLink/pull/32) | 2026-10-14 |
| 2 | Login and session (endpoints 5–7) | **In progress, split** (see below): login done | [#38](https://github.com/assali-mohamed-ali/api-MedLink/pull/38), [#41](https://github.com/assali-mohamed-ali/api-MedLink/pull/41) | — |
| 3 | Staff code, TOTP (endpoints 8–9) | **Done** (MLink-62/63): #97, review round 1 in #99; see the section below; plan and history in [ss3-staff-totp.md](ss3-staff-totp.md) | [#97](https://github.com/assali-mohamed-ali/api-MedLink/pull/97), [#99](https://github.com/assali-mohamed-ali/api-MedLink/pull/99) | 2026-10-29 |
| 4 | Passwords (endpoints 10–11) | **In progress**: MLink-87 merged (#88); MLink-91/88/90 in review | [#89](https://github.com/assali-mohamed-ali/api-MedLink/pull/89) | 2026-10-23 |
| 5 | Accounts and access (endpoints 12–16) | To do | — | — |

**2026-11-19:** Staging and production preparation applied on the hosts: Vault in a container, hosts prepared. **Production works end to end** (dashboard, API through the front, Gmail mail) since the repo owner opened port 8080 and enlarged the staging disk. Staging: dashboard live, API and Keycloak waiting for a fresh Vault setup token. PR #130: the three review findings fixed (CI YAML check, staging dispatch condition, Vault roles bound to the CD workflow, refs and event). No migration, no endpoint, no Java change. See [deploy-prep.md](deploy-prep.md).

**2026-11-18:** Staging and production preparation (MLink-168 to MLink-173, Mohamed) on `chore/vault-deploy`:
MLink-168 Vault as code and MLink-171 host preparation done, scripts and runbooks only (no migration, no endpoint, no
Java change); `ci.yml` gains two steps. Nothing applied on the servers yet. See the section "Staging and production
preparation".

**2026-11-18:** MLink-166 (GitHub #67) on `chore/db-backup-restore`: encrypted backup and all-or-nothing restore of
both databases, scripts and a runbook, restore drill done on the dev stack. No migration, no endpoint, no Java change.
`ci.yml` gains one step. See the MLink-166 section.

**2026-11-26:** MLink-32 follow-up (on `fix/MLink-32-reports-after-consultation`). (1) A practitioner writes a report only once a consultation with the patient has started (a BOOKED appointment whose slot has begun); before, `POST /api/patients/{id}/reports` answers 409 `CONSULTATION_NOT_STARTED` "Vous pourrez écrire le compte-rendu une fois la consultation commencée." and saves nothing. Reading the record and the reports stays possible before (to prepare the consultation). `GET /api/patients/{id}/record` gains `canWriteReport` (true once a consultation has started) so the front offers the editor only then. (2) The patient reads their own reports: new `GET /api/reports/me` (patients only, no id in the URL, as `/api/appointments/me`), newest first, each with `practitioner` (`firstName`, `lastName`, `specialty`), never another patient's; traced as `REPORT_VIEWED` on the patient (32.4). Practitioners and staff 403, anonymous 401. No migration. Tests, red first: `ReportAfterConsultationTest` (6), `MyReportsFunctionalTest` (4), `ConsultationReportFunctionalTest.beforeTheConsultationHasStartedAReportIsRefusedAndNothingIsSaved`, `PatientRecordFunctionalTest` (`canWriteReport`), the access matrix (`GET /api/reports/me`); the existing report tests now book a consultation that has started.

**2026-11-21:** MLink-37, a move is no longer told as a cancellation (on `epic/MLink-10`). When a patient moves an appointment, the practitioner of the old slot gets one `APPOINTMENT_MOVED` alert, "Rendez-vous reporté : <patient>, du <old date> (<old clinic>) au <new date>.", and the mail "Un rendez-vous a été reporté", instead of "annulé". Only the new date is given, never the new practitioner or clinic (it may be another doctor's appointment). A real cancellation is unchanged (`APPOINTMENT_CANCELLED`). `AppointmentService.reschedule()` (MLink-107, Ela) now calls `alerts.onAppointmentMoved(old, moved)` in the same transaction; the mail still leaves after the commit and never blocks it. Migration V21 widens `alerts_type_check` to the two types (no new route, the alert list's shape is unchanged; the front's `AlertType` gains `APPOINTMENT_MOVED`). Tests, red first: `AlertCreationTest.aMoveGivesOneAlertSayingReporteFromTheOldDateToTheNewOne`, `theSameMoveTwiceGivesOneAlertOnly`, `aMoveWhoseOldAppointmentIsNotCancelledGivesNoAlert`, `AlertSchemaTest.theTypeOfAMoveIsKnown`, and `AlertFunctionalTest.movingAnAppointmentTellsThePractitionerItWasMovedAndWhenToWithAMail` (was `…WarnsThePractitionerOfTheOldSlotWithAMail`: the contract changes on purpose) expects the "reporté" alert and mail and no "annulé" mail. `./mvnw -B verify` and the reversed class order green before the push. Code owner's review on the PR: (1) the migration is V21, not V20, since #122 (MLink-74) adds `V20__account_operations.sql`; the chain from V19 was run with Flyway on a fresh database (V1 to V21) and on a database at V19 (master) then upgraded with #122's V20 and this V21: both apply and validate. Merge #122 first. (2) a move to the slot the appointment already holds is refused before any change, 409 `SAME_SLOT` "Ce rendez-vous est déjà sur ce créneau : choisissez-en un autre." (before, the slot was freed then booked again by the same move, with an alert "reporté" from a date to itself). Tests, red first: `AppointmentAuditAtomicityTest.aMoveToTheSameSlotIsRefusedAndChangesNothing`, `RescheduleAppointmentFunctionalTest.aMoveToTheSlotTheAppointmentAlreadyHoldsIsRefusedAndNothingChanges`.

**2026-11-19:** MLink-37 37.7, API part: the agenda shows a cancellation. `GET /api/practitioner/agenda` gives, on a slot nobody holds, its latest cancelled appointment (`appointment.status` `CANCELLED`, the patient's id and names, as for a booking); a booking always wins over an older cancellation; a slot never booked has no `appointment`. One more query, `AppointmentRepository.findCancelledWithPatient` (latest `cancelled_at` first), only for the slots of the week nobody holds. No migration, no new route. Tests, red first: `aBookedSlotShowsItsPatientsNamesAndNothingMoreAndAFreedSlotShowsItsCancelledAppointment` (was `…ACancelledAppointmentIsNotShown`: the contract changes on purpose), `aRebookedSlotShowsItsBookingAndASlotCancelledTwiceShowsTheLatestCancellation`, `afterARealCancellationTheAgendaShowsTheSlotOpenWithTheCancelledAppointment` (book and cancel through the real routes).

**2026-11-18:** MLink-37, code owner's review on #125 (2/2), after #120 (US-15) merged: `master` merged in (conflicts in `report.md` and `MailService`, both sides kept: the cancellation mail and the reminder mail). A real cancellation now creates the practitioner's alert: `AppointmentService.cancel()` calls `alerts.onAppointmentCancelled(appointment)` right after its `APPOINTMENT_CANCELLED` audit row, and `reschedule()` calls it for the old appointment after its `APPOINTMENT_RESCHEDULED` row (a move cancels the old one), both inside the change's transaction; the mail still leaves after the commit and never blocks it. Coordinated with Ela on #120. Tests, red first, through the real routes: the 37.8 story now books and cancels with `POST /api/appointments` and `POST /api/appointments/{id}/cancel` (no direct service call), `movingAnAppointmentWarnsThePractitionerOfTheOldSlotWithAMail`, `aCancelRefusedByTheDeadlineGivesNoAlert`, `cancellingTwiceGivesOneAlert`.

**2026-11-17:** MLink-37, code owner's review on #125 (1/2): the alert list is bounded, since the bell polls it every minute and unread alerts are kept until read. `GET /api/practitioner/alerts` takes `page` (from 0) and `size` (20 by default, 50 at most), sorted newest first then by id, so alerts of the same instant are never repeated or skipped across pages; a page or size out of range (or not a number) is 400 `VALIDATION_FAILED` naming the parameter, as on `/api/admin/users`. The answer is still a JSON array: the front's bell is unchanged. Tests, red first: `theListIsPagedTwentyAtATimeByDefaultNewestFirst`, `alertsOfTheSameInstantArePagedInOneStableOrder`, `aPageSizeAboveFiftyOrAPageOutOfRangeIsRefusedNamingTheParameter`; Postman folder 12 gains size=50 (200) and size=51 (400). (2/2), the wiring into the real cancellation, waits for #120 (US-15) to merge first.

**2026-11-17:** MLink-37, before the PR: `master` merged in (V16 then V17; conflicts only in this file and `AccessMatrixFunctionalTest`, both sides kept). Postman folder 12 "Cancellation alerts": a patient gets 403 on both routes, a disposable practitioner (code step included) gets 200 and an empty list (unread and `unread=false`), 403 for an alert that is not theirs or an id that is not a UUID, anonymous 401. An alert itself needs a cancelled appointment, which no endpoint creates yet (US-15): proven by `AlertFunctionalTest`, `AlertCreationTest`, `AlertMailFunctionalTest`.

**2026-11-13:** MLink-37 37.8, the acceptance criteria and their tests. One end-to-end story, `AlertFunctionalTest.anAlertReceivedWhileLoggedOutIsThereAtTheNextLoginWithItsMailThenMarkedRead`: the practitioner logs out, the appointment is cancelled, (1) the alert row and the mail exist (read in Mailpit), (2) logged in again the alert is still listed unread, (3) once marked read it leaves the list, also after another login, (4) another practitioner neither sees it nor marks it (403). Details per criterion: `AlertCreationTest` (one alert, Tunis time, not twice, a failing mail never blocks), `AlertMailFunctionalTest` (the mail's content), the other `AlertFunctionalTest` cases (list shape, ownership, roles), `AlertSchemaTest` (table rules). The cancel route itself is US-15's (epic MLink-8): the story calls `AlertService.onAppointmentCancelled` as its cancel code will. `./mvnw -B verify` and the reversed class order green before the push.

**2026-11-13:** MLink-37 37.4 (H-06): with its alert, the practitioner gets a mail "Un rendez-vous a été annulé" (`MailService.sendAppointmentCancelled`, French, plain text and HTML): the patient's name, the date and time in Tunis, the clinic and a link to the agenda; no medical content, no birth date or phone. It leaves after the commit (`TransactionSynchronization.afterCommit`), so never for a rolled-back cancellation, and never blocks it: a failure is logged by the alert id only (no address, no name) and the alert stays. No alert, no mail (not cancelled, already alerted). Tests: `AlertMailFunctionalTest` (the mail read in Mailpit), `AlertCreationTest` (a failing mail keeps the alert, throws nothing).

**2026-11-13:** MLink-37 37.2: `AlertService.onAppointmentCancelled(appointment)` gives one `APPOINTMENT_CANCELLED` alert to the practitioner of the slot: "Rendez-vous annulé : <patient>, <jour date à HH:mm Tunis>, <clinic>." Nothing for an appointment that is not cancelled; one alert per cancellation (checked, and held by the V17 unique constraint), so a cancel processed twice changes nothing. It joins the caller's transaction: the cancellation and its alert are kept together. The cancel route is US-15's (`AppointmentService.cancel`, epic MLink-8, not merged): until then the method is tested directly (`AlertCreationTest`); its owner calls it after `slot.setStatus(FREE)` in `cancel()` (asked on their ticket, their file is not changed here).

**2026-11-13:** MLink-37 37.3: `GET /api/practitioner/alerts?unread=true` (the default; `false` gives all) → the caller's alerts newest first, `{ id, type, message, appointmentId, createdAt }`, the contract the front's bell was built on (37.5); `POST /api/practitioner/alerts/{id}/read` → 204, harmless when repeated (the first reading time stays). Ownership: every query is scoped to the caller (`findByIdAndPractitionerId`), so another practitioner's alert, an unknown one and a malformed id get the same 403. `OwnershipGuard` is not used here on purpose: it lets staff through, and an alert's owner is only known by reading it; the URL rule `/api/practitioner/**` (#116) already keeps staff and patients out before anything is read. `Alert.createdAt` kept to the microsecond (as #123). Access matrix: two rows. `master` merged first (V15 now there, V16 still on #123).

**2026-11-12:** MLink-37 37.1 (`feat/MLink-37-cancellation-alerts`): `V17__alerts.sql`, the practitioner's alerts, kept until read (H-06: an offline practitioner finds them at the next login). Columns of the ticket; `practitioner_id` references `practitioners` (= `users.id`, stricter than `users`: only a practitioner gets an alert); `type` checked (`APPOINTMENT_CANCELLED`); one alert per (type, appointment), so a cancellation processed twice gives one alert (37.2); partial index for the unread list. Entity `Alert`, `AlertType`, `AlertRepository` (unread newest first, own alert by id, already alerted). **V17 because V15 (#116, agenda) and V16 (MLink-32, reports) come first: this branch merges after both.**

**2026-11-17:** MLink-165 (GitHub #57) on `fix/db-hardening`: two database roles. Flyway migrates as the owner
(`POSTGRES_USER`), the API connects as `medlink_app`, which may only append to and read the audit trail and cannot
change the schema. **Migration V18** (`V18__app_role_grants.sql`; V17 is MLink-37's, now present from `master`). **Every test now runs as
`medlink_app`** (`TestcontainersConfiguration`, shared file): a new test that needs a privilege the API does not have
will fail, as the API would. New env key `APP_DB_PASSWORD` (the setup scripts add it to `.env`); staging/prod also need
`SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD`. No `-Reset` needed. No endpoint. See the MLink-165 section.

**2026-11-18:** MLink-167 on `epic/MLink-8` (story MLink-27): a patient cannot hold two overlapping appointments,
even in two clinics. A booking or a move onto such a time is **409 `PATIENT_BUSY`**. No migration, no new route,
no shared file changed. See the MLink-167 section.

**2026-11-17:** Code owner's review on #120, round 3: a cancel writes `APPOINTMENT_CANCELLED` and a move writes
`APPOINTMENT_RESCHEDULED` (on the new appointment), each in its own transaction like a booking. `AuditAction` gains
the two values (shared file, two lines). No migration, no new route. See the section "Review on #120".

**2026-11-15:** Code owner's review on #120 (`epic/MLink-8`), three fixes: a booking and its audit row in one
transaction (MLink-104); each reminder locks and rechecks its appointment before the mail, so a cancel during the run
stops it (MLink-110); reminder delivery is stated as **at least once**, with one Message-ID per appointment (MLink-111).
Shared file: none changed (`AuditService.recordWithin` came with #116). No migration, no new route. See the section
"Review on #120".

**2026-11-08:** MLink-111 on `epic/MLink-8`: a failed reminder is tried 3 times then logged (id only) and left for the
next run; one reminder only. New property `app.appointments.reminder-retry-backoff`. See the MLink-111 section.

**2026-11-08:** MLink-110 on `epic/MLink-8`: a **scheduled job** mails a reminder 24 hours before a booked appointment
(H-02 = `app.appointments.reminder-hours-before`). No route, no migration. See the MLink-110 section.

**2026-11-08:** MLink-107 on `epic/MLink-8`: **new route `POST /api/appointments/{id}/reschedule`** (move to another
slot, all or nothing); one new line in `AccessMatrixFunctionalTest`. No migration. See the MLink-107 section.

**2026-11-08:** MLink-106 on `epic/MLink-8`: **new route `POST /api/appointments/{id}/cancel`** (the holder cancels up
to 12 hours before, H-03 = `app.appointments.cancel-deadline-hours`); one new line in `AccessMatrixFunctionalTest`;
**`SecurityConfig`** (shared): every POST under `/api/appointments` is PATIENT-only in the filter chain. No migration.
`QrCodesTest` (MLink-62/63) errored once in the reverse order and passed on reruns: reported as flaky. See the
MLink-106 section.

**2026-11-08:** MLink-108 on `epic/MLink-8`: **new route `GET /api/appointments/me`** (a patient's own appointments,
latest first, cancelled included); one new line in `AccessMatrixFunctionalTest`. No migration. See the MLink-108 section.

**2026-11-08:** MLink-104 on `epic/MLink-8`: every booking writes one audit line (`APPOINTMENT_BOOKED`, SUCCESS,
ids only). `AuditAction` and `AuditOutcome` gain a value each (`SUCCESS` is also added by MLink-114 and epic MLink-9).
No migration, no new route. See the MLink-104 section.

**2026-11-08:** PR #87 (epic MLink-8, first batch: MLink-97, 98, 99, 100, 163, 102, 103) **merged into `master`**
(f71c577). `epic/MLink-8` goes on for the rest of the epic. Post-merge review fix on it: **`SecurityConfig`** (shared)
makes `POST /api/appointments` PATIENT-only in the filter chain, so a malformed body from a non-patient is 403, not
400. See the MLink-102 section.

**2026-11-12:** MLink-32 (`feat/MLink-32-consultation-report`): 32.1 `V16__consultation_reports.sql` (immutable rows, content sealed only), 32.2 `ReportCipher` (AES-256-GCM, own key `APP_REPORT_ENCRYPTION_KEY`, report and patient ids as associated data), 32.3 `POST`/`GET /api/patients/{id}/reports` for the practitioners the patient is attached to (the record's rule, MLink-31): body `{ content }` (not blank, 10 000 characters at most, `details.content` on a 400), answer `{ id, createdAt, content }`, newest first; any other caller gets the record's 403, checked before the text; a failed save answers 500 `REPORT_SAVE_FAILED` and keeps nothing. Shared files: `SecurityConfig` (one URL rule, `/api/patients/*/reports` for PRACTITIONER, so a patient's bad body is 403, not 400), two rows in `AccessMatrixFunctionalTest`, Postman folder 11. The migration is V16: this branch merges after #116 (V15).

**2026-11-12:** MLink-32 32.4: every report written adds one `REPORT_CREATED` row (resource `CONSULTATION_REPORT`, the report id), in the same transaction as the report: a report is never kept without its trace, and a failed row answers `REPORT_SAVE_FAILED`. Every read adds one `REPORT_VIEWED` row (resource `PATIENT_REPORTS`, the patient id) before the list leaves, like the record's `RECORD_VIEWED`. Ids only, never a text. Refusals stay `ACCESS_DENIED`. `AuditService.recordWithin` is the same method as on #116 (identical lines, so the two branches merge cleanly there); `AuditAction` gains two values (an additive conflict with #116's `SLOT_OPENED`/`SLOT_CLOSED` when both merge).

**2026-11-15:** MLink-32, code owner's second review on #123. (1) `ReportWriteGuard` refuses every write of unknown length with 411, not only a chunked one: an HTTP/2 hop may forward a body with neither `Content-Length` nor `Transfer-Encoding`, and only a stated length is a bound Tomcat enforces. Tests: `ReportWriteGuardTest` (HTTP/2 and HTTP/1.1 forms, unit), `aWriteWithNeitherContentLengthNorTransferEncodingIsRefusedWith411` (raw socket, real API). (2) `report_encryption_key` is in the three `terraform.tfvars.*.example` files and in the README's required settings (distinct from the MFA key, back it up); `TerraformExamplesTest` checks that every variable without a default is in each example and in the README. Red first.

**2026-11-14:** MLink-32, code owner's review on #123. (1) A report write is checked before Spring reads its body: `ReportWriteGuard`, a handler interceptor on `/api/patients/*/reports` (registered in the new `WebConfig`), checks the attachment first (403 for a malformed, huge or unsized body from a practitioner who is not attached), then a hard size: no `Content-Length` (chunked) is 411 `LENGTH_REQUIRED`, over 64 KiB is 413 `PAYLOAD_TOO_LARGE` (10 000 characters at their longest JSON escape still fit). The service keeps its own checks. (2) A traced read no longer holds its read transaction's connection while its audit row is written in its own (`REQUIRES_NEW`): `list` reads in a read-only `TransactionTemplate`, then calls `audit.record`. Same fix for `PatientRecordService.read` (MLink-31, same pattern). Tests, red first: three in `ConsultationReportFunctionalTest`, and `ReadAuditConnectionTest` (a pool of one connection: a read holding two would time out; the committed row is read back; a read whose trace fails is not answered). No migration, no new route; `SecurityConfig` unchanged.

**2026-11-13:** MLink-32, CI of #123: a report's `createdAt` is kept to the microsecond, as PostgreSQL stores it. Linux clocks (the CI's) give nanoseconds, so the POST answered a time the GET never gave back (green on macOS, red on Linux). Test `theCreationTimeKeptByTheEntityIsTheOneTheDatabaseStores`.

**2026-11-12:** MLink-32 32.7, the acceptance criteria and their tests: (1) the content column is not the text: `theContentIsSealedInTheDatabaseNeverInPlainText`, and `theSameTextIsSealedDifferentlyEachTimeAndNeverAsItsPlainBase64` (a new IV each time, no plain base64); (2) writing for a patient not attached is 403 and saves nothing: `aPractitionerWhoIsNotAttachedGetsTheRecordsSame403AndNothingIsSaved`; (3) a failing save answers `REPORT_SAVE_FAILED` and stores nothing: `ReportSaveFailureTest` (the database cannot fail on demand through the API, so the service is called with its repository, then its audit, made to fail); (4) write and read are audited: `everyWriteAddsOneReportCreatedEntryWithTheIdsOnly`, `everyReadAddsOneReportViewedEntryForThePatient`. `./mvnw -B verify` and the reversed class order green before the push.

**2026-11-12:** MLink-26, code owner's second review on #116: a slot change and its audit row are now one unit. The slot was committed before its audit row (written in its own `REQUIRES_NEW` transaction), so a failed audit could answer 500 after a kept change, with no trace. `AuditService.recordWithin` (new, `MANDATORY`) writes the row in the change's transaction; `AgendaService` calls it for an opening, a closing and a reopening, and the controller no longer audits. If the row cannot be written, the change rolls back. `AuditService.record` (own transaction) is unchanged for refusals. New test `AgendaAuditAtomicityTest` (audit repository made to fail). Shared file: `AuditService` (one method added). No migration, no new route.

**2026-11-10:** MLink-26, code owner's review on #116: `SecurityConfig` gains a role rule for `/api/practitioner/**` (the role is checked before Spring converts dates and JSON: 403, never 400, for a patient). Shared file: no other route changes. Also fixed: two overlapping slot openings at once could end in a 500 (Postgres deadlock), now 409. No migration, no new route.

**2026-11-10:** MLink-26 (`feat/MLink-26-agenda`): `master` merged after #87 and #113, so the agenda migration is renumbered **V15** (`V15__agenda_reference_data.sql`; V14 is MLink-99's slot search index). Dev databases that ran the branch's old V14 need the setup script with `--reset` (`-Reset`) once. Conflicts kept both sides (`SlotRepository`, access matrix). New test: a closed slot cannot be booked. See the MLink-26 section.

**2026-11-11:** MLink-164 (GitHub #64) on `fix/db-hardening`: staging and prod refuse to start without real database
credentials, and `docker-compose.yml` requires `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`. No migration, no
endpoint. **Before the next deploy:** the deploy secret (`DEPLOY_ENV_FILE`) must hold those three keys with real
values. One PR with #57 (MLink-165, next). See the MLink-164 section.

**2026-11-10:** MLink-31 (`feat/MLink-31-patient-record`): `GET /api/patients/{id}/record` for the practitioners the patient is attached to (a `BOOKED` appointment). No migration. **One new line in `AccessMatrixFunctionalTest`**, one new rule in `SecurityConfig`. See the MLink-31 section.

**2026-10-31, updated 2026-11-08:** MLink-65 (sub-sprint 3 part, `feat/ss3-setup-staff-code-check`): both setup
scripts check the session with `GET /api/auth/me` (#107): `patient.dev` gets 200 with exactly the roles `["PATIENT"]`, and
`practitioner.dev`'s password alone opens no session (login 200 with `mfaRequired`, then 401). Scripts and README
only: no migration, no endpoint.

**2026-11-07:** MLink-103 on `epic/MLink-8`, PR #87: two patients booking one slot at once get one appointment and
one 409 `SLOT_TAKEN`; a double booking only the unique index catches also answers 409, never 500. No migration (the
index is in V13), no new route. See the MLink-103 section.

**2026-11-07:** MLink-102 on `epic/MLink-8`, PR #87: **new route `POST /api/appointments`** (patients book a slot);
one new line in `AccessMatrixFunctionalTest`. No migration. **New dev user `patient.demo@medlink.local`** (dev password):
`patient.dev` has no patient profile, so it cannot book (403 `PATIENT_PROFILE_REQUIRED`); `setup-dev` imports the new
user at the next start. See the MLink-102 section.

**2026-11-07:** MLink-163 on `epic/MLink-8`, PR #87: **new route `GET /api/slots/filters`** (patients only, the
lists of the search form); one new line in `AccessMatrixFunctionalTest`. No migration. See the MLink-163 section.

**2026-11-07:** MLink-99 (slot search index) on `epic/MLink-8`, PR #87: **migration V14**
(`V14__slot_search_index.sql`, one partial index on the free slots); dev databases need the setup script once
(V14 runs on start, no `--reset` needed). No new route. See the MLink-99 section.

**2026-11-07:** `epic/MLink-8` (PR #87): current `master` merged (Scalar instead of Swagger UI, so the SCA run is
green again); `GET /api/slots` now checks the role before reading any parameter, so a non-patient gets 403 even with
`clinicId=abc` (code owner's review, see the MLink-100 section). The migration stays **V13**. No new route.

**2026-10-29 (night):** MLink-53, `GET /api/auth/me` (endpoint 6, `feat/mlink-53-auth-me`): the user object of the
login, read from the session the login opened (the id token and the granted roles), with no Keycloak or database
call; 401 `UNAUTHENTICATED` without a session, after a logout, or with only a pending code step. React needs it to
restore who is logged in after a page refresh (the front's MLink-128). Taken over from Ahlem with her agreement. No
migration. The tests and setup scripts that check a session with `/api/users/_health` can now use it.

**2026-10-29 (evening):** MLink-68 (story MLink-19) on `feat/mlink-68-lockout-check`: `scripts/check-lockout.sh`
proves the lockout on the running dev stack: 5 wrong passwords 1.2 s apart (Keycloak's quick-login guard would lock
after 2 otherwise), then the right password is refused (401, like a wrong password: a 423 would reveal that the
account exists) and Keycloak's brute-force status says locked; `patient.dev` is unlocked at the end. `PASS` on the dev
stack, `FAIL` with `failureFactor` raised to 50 through the admin API. No migration, no endpoint.
Review of #103 (2026-10-30): the script refuses a non-local `APP_URL` or `KEYCLOAK_URL` before any request (exit 2),
so a mistaken staging/prod URL cannot lock or unlock a real account; a remote dev stack needs
`ALLOW_REMOTE_DEV_STACK=yes-this-is-a-dev-stack`. `scripts/check-lockout.test.sh` (offline, fake `curl`, run by CI's
`build-test`) covers 14 cases, lookalikes included (`localhost.evil.com`, `localhost:8180@evil.com`); two mutants of the
guard turn it red. The cookie jar is removed in the `EXIT` trap.
Review of #103 (2026-11-02): the unlock is now checked. It needs an admin token and Keycloak's 204; otherwise it says
why on stderr and the run fails (exit 1, never `PASS`), at the start (the count would prove nothing), at the end (the
unlock runs before the result is printed) and in the `EXIT` trap (an interrupted run). The offline test plays a fake
dev stack: final unlock answered 500 or 401, no admin token, first unlock refused (19 cases); mutants without the status
check or the token check turn it red. `PAUSE_SECONDS` (default 1.2) lets the test skip the waits.
Review of #103 (2026-11-06): the guard read `https://prod.example.com#@localhost` (or `?@`, `\@`) as localhost,
because it dropped the user-info before the query and fragment: the admin and test passwords would have gone to
prod. `url_host()` now cuts the host at the first `/`, `?`, `#` or `\` before dropping the user-info; 5 new cases
(24 in all), red before the fix.

**2026-10-29 (evening), updated 2026-10-30:** MLink-127 follow-up and GitHub #65 on `fix/65-swagger-off-in-prod`:
staging and prod serve neither the API reference UI nor `/v3/api-docs` (404 over HTTP, `ApiDocsExposureIntegrationTest`),
dev keeps both (DAST runs the dev profile). After #100 (Scalar replaces Swagger UI) the UI is Scalar at `/scalar`:
`scalar.enabled: false` in staging and prod switches it off on its own (today `springdoc.api-docs.enabled: false` also
hides it, as a side effect of Scalar's configuration depending on springdoc's; staging mutants: no switch exposes
`/scalar`, `/scalar/scalar.js` and `/v3/api-docs`; `scalar.enabled: false` alone hides Scalar). The test lists
`/scalar`, `/scalar/scalar.js` and the old Swagger UI paths, with soft assertions. The DOMPurify 3.4.16 suppression
of the first version is dropped: Swagger UI is no longer a dependency and Scalar's bundle embeds no DOMPurify.
No migration, no endpoint.

**2026-10-29:** `epic/MLink-8` (PR #87, ready for review): `master` merged after #97 and #99, so the appointment
migration is renumbered **V13** (`V13__appointment_domain.sql`); dev databases that ran an older number need the
setup script with `--reset` (`-Reset`) once. Code owner's review: a slot must be in one of its practitioner's clinics
(composite foreign key), see the MLink-97 section. No new route.

**2026-10-29:** #97 (MLink-62/63) was merged before its review round 1 reached it, so `master` still has the
first-enrolment takeover and the `/patients` default group. `fix/ss3-review-round-1` (one PR based on `master`)
brings that round: the first setup needs a link mailed at login (**migration V12**), the realm adds no default group
(staging and prod: a console step, see `docs/DEPLOYMENT.md`), and a login holding two MedLink roles is refused. Also
the README tells manual-setup users to replace the `APP_MFA_ENCRYPTION_KEY` placeholder of `.env.example`. SCA on
`master` is red again on a new finding, CVE-2026-116803/116804 (10.0) in DOMPurify 3.4.16 (Swagger UI 5.34.0): a
separate MLink-127 follow-up, not this PR.

**2026-10-28:** MLink-127 (SCA baseline, `chore/sca-baseline`): since the NVD key is set (2026-10-27), the
SCA workflow runs OWASP Dependency-Check instead of the Trivy fallback, and it scans the whole project, so findings
already on `master` turned **every PR red**. The only finding with CVSS ≥ 7, `spring-boot-devtools` vs
CVE-2022-31691 (9.8), is a false positive: the CVE is about the Spring Tools IDE plugins (CPE mismatch), and devtools
is `optional`, so the production jar leaves it out. It gets a narrow suppression in `security/owasp-suppressions.xml`
(this package and this CVE only, until 2027-01-31). springdoc 3.1.1 → 3.2.0 brings Swagger UI 5.34.0, whose bundled
DOMPurify 3.4.16 fixes CVE-2026-109344/109345 (6.1, XSS). No migration, no endpoint.

**2026-10-25:** MLink-62 and MLink-63 (sub-sprint 3, staff code) on `feat/ss3-staff-totp`, one PR based on
`master`. **Migration V11** (`user_mfa`). **New required secret `APP_MFA_ENCRYPTION_KEY`**: the API does not start
without it; staging and prod need it in `DEPLOY_ENV_FILE` before the merge (see the section below).
Practitioners and staff now log in with a code: tests that log in as them use `Browser.login()`, which does the
code step.

**2026-10-25:** MLink-122 (GitHub #49): in staging and prod, Actuator (metrics, `/actuator/health`) moves to the
private management port 9000; the public port keeps `/livez` and `/readyz`. **Every health check now calls `/readyz`**
(Dockerfile, Terraform, the deploy action's `DEPLOY_HEALTH_URL`): the code owner sets the GitHub variable
`DEPLOY_HEALTH_URL` to `https://<api-host>/readyz` before the next deployment. Dev unchanged. No migration, no endpoint.

**2026-10-24:** Presentation edition of this report for the team and the jury: `report-v2.md`
(source) and `report-v2.html` (rendered), screenshots and diagrams in `assets/`; figures as of `master`
`d7b0585` (2026-10-23). For a PDF, print the HTML (A4 is set in its CSS); the PDF is not committed (2.4 MB,
over the 1 MB limit of the large-file hook in #91). This file stays the engineering log. No code change.

**2026-10-24:** MLink-100 (slot search input rules, "no result" message) on `epic/MLink-8`, draft PR #87: no
migration, no new route.

**2026-10-23:** `master` merged into `epic/MLink-8` after #89: the appointment migration is renumbered
**V10** (`V10__appointment_domain.sql`), since #89 merged first with `V9__session_revocations.sql` (the code owner's
call on #87: the PR merged second takes the next free number). Conflicts kept both sides: the access matrix lines,
this report, and the Postman collection (the slot search becomes folder 7, after #89's folder 6). Dev databases that
ran the old V9 need the setup script with `--reset` (`-Reset`) once.

**2026-10-23:** MLink-98 (slot search, `GET /api/slots`, the epic's first endpoint) on `epic/MLink-8`, draft PR #87:
no migration. **One new line in `AccessMatrixFunctionalTest`.**

**2026-10-23:** MLink-91, MLink-88 and MLink-90 on `feat/ss4-password-change`, one PR based on `master`.
Endpoints 10 and 11 work end to end; see the section below for the proofs.

**2026-10-22:** MLink-97, code owner's review on draft PR #87 addressed on `epic/MLink-8`: one active appointment
per slot is now a database rule (partial unique index in V9), and the three dev practitioners left the shared realm
file for a dev-only file imported by a dev-only compose service. **The realm file is back to `master`'s**; a new
service `keycloak-dev-users` appears in `compose.yaml` only (`ComposeFilesTest` gains a `DEV_ONLY` list).

**2026-10-21:** MLink-97 (epic MLink-8, appointments: tables and dev seed) ready on `epic/MLink-8`: migration
**V9** (after V8 `rate_limits`), no endpoint. The endpoints of the epic (MLink-98…) come next on the same branch.

**2026-10-20:** MLink-119 (GitHub #66, part 1) in review (#78): README rewritten to match `master` (password-grant
login, auth flow diagram, endpoints, security rules, team workflow, CI). No code change. Part 2:
`docs/ARCHITECTURE.md` and `security/threat-model.md`.

**2026-10-20:** MLink-120 (GitHub #66, part 2): `docs/ARCHITECTURE.md` and `security/threat-model.md` match
`master` (password-grant login, trust boundaries, STRIDE controls mapped to code and tests, ISO 27001 rows, open
work by priority). No code change. **New gap found:** the realm role `ADMINISTRATIVE_STAFF` holds Keycloak's
`manage-users` and `view-realm`, so staff could manage users in Keycloak's console outside the API (threat model
E3), tracked in GitHub #80 and fixed in #81 (MLink-121); E3 is ✅ in the threat model.

**2026-10-20:** MLink-60 (role × route matrix, the last task of MLink-16 RBAC) ready on `epic/MLink-6`: no
migration, no endpoint. **From now on every new `/api` route needs a line in `AccessMatrixFunctionalTest`, or
the build fails** (see its section). Found while checking the dev stack: since #74 (postgres 18) `setup-dev`
fails on `master` for everyone, even with empty volumes; reported on #74, not fixed here (tests still use 16).

**2026-10-20:** MLink-121 (GitHub #80, threat model E3): the realm role `ADMINISTRATIVE_STAFF` no longer holds
Keycloak admin rights. Realm file change only, no migration, no endpoint. **Staging and prod need the same change
by hand in the Keycloak console** (see its section); locally the setup script re-imports the realm (dev volumes
wiped, realm saved first).

**2026-10-19:** MLink-84 (session cookie hardening: SameSite=Strict, Secure in staging/prod) in review (#63): no
migration, no endpoint.

**2026-10-18:** MLink-112 (GitHub #51, shared rate limits, a condition of #58 option 1) merged (#60). **Migration numbers settled (code owner's review, 2026-10-19): V8 is
`rate_limits` (this PR, ready first); Ela's MLink-97 takes V9.** The PR that merges first must hold the lower
number: a database that already applied V9 makes Flyway refuse a V8 that arrives later.

**2026-10-19:** MLink-113 (GitHub #52, one compose setup per workflow) merged (#61): no migration, no endpoint.

## MLink-174 — reference specialties in every environment (2026-11-19, feat/MLink-174-specialties)

- Found while preparing production: staging and production had no specialty (only the dev seed inserts them), and a
  practitioner needs one (`specialty_id NOT NULL`), so an admin could not create a practitioner there.
- `V19__specialties_reference_data.sql`, like V15 for the clinics: 15 usual consultation specialties (Médecine
  générale, Cardiologie, Pédiatrie, Dermatologie, Gynécologie-obstétrique, Ophtalmologie, ORL, Neurologie, Pneumologie,
  Gastro-entérologie, Endocrinologie, Rhumatologie, Psychiatrie, Urologie, Néphrologie) with `ON CONFLICT DO NOTHING`.
  The first three keep the dev seed's ids, so the dev seed skips them. Mohamed had no official list (2026-11-20): this
  one is a proposal; a change goes in a new migration (no endpoint creates a specialty).
- Test: `AppointmentSchemaTest.outsideTheDevProfileTheReferenceSpecialtiesExistWithTheDevSeedIds` (red commit first).
  Existing tests insert specialties with their own suffixed names and check `/api/slots/filters` with `contains`, so
  the new rows do not disturb them. One exception: `DevSeedTest.theThreeSpecialtiesAreSeeded` expected exactly the
  seed's three; it now checks they are among the specialties (`containsAll`), since V19 adds the others everywhere.

## Staging and production preparation — MLink-168 to MLink-173 (2026-11-18, chore/vault-deploy)

The repo owner created four EC2 hosts (staging, production API, production front, Vault; Ubuntu 26.04, t3.micro)
and gave Mohamed their launch keys to prepare the deploys of both repositories. Only the repo owner has the AWS
console and Vault's root token. Hosts, roles and edges: [docs/operations/servers.md](../../operations/servers.md).
**Plan and hand-over for the next session: [deploy-prep.md](deploy-prep.md).**

Found on the hosts (read-only check, 2026-11-18): Vault 1.21.0 already initialised and unsealed behind Caddy (TLS via
sslip.io), Caddy edges on staging and front, ufw active, Docker 29 on the app hosts, empty `/opt/medlink`, nothing
running. **Too small for the API stack:** 908 MB of RAM and about 2 GB of free disk per host, against about 2.5 GB of
RAM for API + Keycloak + two Postgres: staging and api to resize (t3.medium, 20 GB), after Elastic IPs.

| Ticket | What | State |
|---|---|---|
| MLink-168 | Vault as code: `vault/` (policies, `setup.sh`, `seed-env.sh`, `install-host.sh`, `snapshot.sh`), runbook [vault.md](../../operations/vault.md) | applied: Vault 1.21.0 in a container on the vault host, secrets seeded |
| MLink-171 | Host preparation: `deploy/prepare-host.sh` (deploy user, restricted key, SSH keys only, security updates, Docker log rotation, daily backup job), runbook [servers.md](../../operations/servers.md) | applied on the staging, api and front hosts |
| MLink-169 | API deploy reads Vault; staging deploy job (master's `cd.yml` has none); prod split across api and front | done on the branch; production API running; CD runs after the merge |
| MLink-170 | Front deploy: extend the CD already in `medlink-dashboard` (`container-security.yml`), container on `127.0.0.1:8081` only | dashboard PR #20; production front running |
| MLink-172 | Mail through the team Gmail account (SMTP, app password typed by its holder into `seed-env.sh`) | config ready; waits for the app password typed by its holder |
| MLink-173 | First staging deploy checked end to end, then production | production up behind the security group; staging waits for a larger disk |

- **Vault design.** KV v2 at `medlink/<staging|prod>/<api|front>`; GitHub Actions log in with their OIDC token
  (JWT auth at `jwt-github/`), one role per repository and environment bound to the `sub` claim
  (`repo:assali-mohamed-ali/<repo>:environment:<env>`), audience `medlink-vault`, read-only on its own path,
  10-minute tokens. The set-up runs with a short-lived `medlink-setup` token, never root; secrets are generated on the
  vault host and never shown. Audit log (rotated) and daily raft snapshot.
- **Tests.** `vault/test/vault.test.sh` (58 checks, throwaway Vault 1.21.0 + Ubuntu 26.04: each role reads only its
  path; wrong repository, environment, audience, key, event or an expired token refused; no value in clear in the
  audit log; seed never overwrites) and `deploy/test/prepare-host.test.sh` (46 checks on Ubuntu 26.04: refusals before
  any change, users, modes, `sshd -t` and the effective settings, idempotence, key rotation, the front gets no backup
  job). Both in CI. Each committed red then green; rules broken on purpose in a copy (3 for Vault, 3 for the hosts)
  each turned a test red.
- **Found on the way:** `docker-compose.yml` has no `restart:` policy, so containers do not come back after a reboot
  (fix with MLink-169).
- **Waiting on the repo owner** (#125, #126, #129 merged 2026-11-18): the resize and the new IPs; a `medlink-setup` token;
  `staging` brought up to `master`, branch rules, GitHub environments in both repositories; an S3 bucket for the
  backups and Vault snapshots; the front's `Production` branch renamed `prod`. He proposes Terraform for the hosts (scope not agreed yet).

## MLink-166 — backup and tested restore of both databases, GitHub #67 (2026-11-18, chore/db-backup-restore)

Part C of the database hardening (Parts A and B, #64 and #57, are in PR #126). No migration, no endpoint, no Java
change.

- `scripts/db-backup.sh <dir>`: `pg_dump --format=custom` of the app database, then Keycloak's, inside each
  container with its own credentials; encrypted on the way out with gpg (AES-256, integrity-protected), so nothing in
  clear touches the disk; each file decrypted in full once and checked to be a pg_dump archive before it gets its
  final name. Refuses a passphrase under 20 characters, a missing directory, or a directory inside the repository.
- `scripts/db-restore.sh <app|keycloak> <file> --yes-overwrite`: decrypts and checks the whole file first (a wrong
  passphrase or a damaged file never reaches the database), then `pg_restore --clean --if-exists --no-owner
  --single-transaction --exit-on-error`: fully restored or unchanged.
- **Change from the plan:** gpg instead of `openssl enc -aes-256-cbc`. CBC has no integrity check, so a damaged or
  altered backup would only show up halfway through a restore; gpg refuses it before.
- The passphrase comes from the environment only and reaches gpg through a file descriptor: never an argument, never
  printed. The test checks this on every output, on docker's arguments and on gpg's.
- **Tests:** `scripts/db-backup.test.sh`, 17 cases, offline with a fake `docker` and the real gpg; new CI step in
  `ci.yml` (code owner's file: one step). Committed red, then green. Each rule broken once in a copy of the scripts
  (passphrase as an argument, no check before restore, no repository guard, no partial file, no single
  transaction): the test turned red every time. shellcheck 0.10.0: clean.
- **Drill (2026-11-18, dev stack):** marker rows in both databases, backup (3 s), both volumes deleted with
  `docker compose down -v`, restore into empty databases (2 s), the API up and its checks passed in 53 s:
  `patient.dev` logs in, `practitioner.dev` is asked for its code, a signup works; the Keycloak id of `patient.dev`
  is unchanged. First attempt refused: the dump grants rights to `medlink_app` (#57) and the empty database had no
  such role. Nothing was restored (one transaction). After #126, a new volume gets the role from
  `docker/postgres/initdb/10-app-role.sh`; the runbook has the fix.
- **Runbook:** [docs/operations/backup-restore.md](../../operations/backup-restore.md): policy (daily, 7 daily + 4
  weekly, RPO 24 h, RTO measured), cron line, off-host copy, restore and verification steps, drill log.
- **For whoever runs the server:** install `gnupg`, put `BACKUP_PASSPHRASE` in the team's secret store and in a
  root-only env file, add the cron line and the off-host copy (runbook, "Set up on a server"). None of this is in the
  deploy action yet.

## MLink-165 — two database roles, the audit trail out of the API's reach, GitHub #57 (2026-11-17, fix/db-hardening)

Plan and step-by-step history: [db-hardening.md](db-hardening.md) (Part B).

**Built:**
- `docker/postgres/initdb/10-app-role.sh`: the one place that creates the runtime role `medlink_app` (login, password
  from `APP_DB_PASSWORD`, no superuser or create rights). Run by the postgres image on a new volume (both compose
  files and the tests' container), and by the setup scripts on an existing one. Safe to run again.
- `V18__app_role_grants.sql`: `SELECT, INSERT, UPDATE, DELETE` on the app's tables, **only `SELECT, INSERT` on
  `audit_entries`**, nothing on `flyway_schema_history`, sequences, and default privileges for the tables of later
  migrations. No ownership, so no `ALTER`, `DROP`, `TRUNCATE` or `TRIGGER`; no `CREATE` in the schema. It creates
  the role without login when missing (an old volume), so it never fails there.
- Staging and prod: `spring.flyway.user/password` (`SPRING_FLYWAY_USER` / `_PASSWORD`, no default) is the owner,
  `spring.datasource.*` the runtime role. `DatabaseCredentialsCheck` applies the #64 rules to the Flyway values too,
  and **refuses to start when the datasource user is the Flyway owner** (the protection would be gone silently).
- `docker-compose.yml`: the app connects as `medlink_app` with `APP_DB_PASSWORD` (required), Flyway as
  `POSTGRES_USER`; postgres mounts the init script. `compose.yaml` the same with dev values. Terraform:
  `db_flyway_username` / `db_flyway_password`. Setup scripts: generate `APP_DB_PASSWORD`, run the init script before
  the API starts.
- Tests: `TestcontainersConfiguration` limits the service connection to Flyway (owner) and gives the datasource its own
  `JdbcConnectionDetails` as `medlink_app`.

**Found on the way:**
- A plain `@ServiceConnection` (and Spring Boot's compose integration) gives the container's owner to **both** the
  datasource and Flyway; connection details win over `spring.flyway.user`. Hence `type = FlywayConnectionDetails.class`.
- A `DynamicPropertyRegistrar` comes too late for a `RANDOM_PORT` test: the embedded server opens the pool in
  `onRefresh`, before registrars run. Hence a connection-details bean.
- Git Bash on Windows rewrites `/docker-entrypoint-initdb.d/...` into a Windows path: `MSYS_NO_PATHCONV=1`.

**Proof:** `RuntimeRoleGrantsTest` (the app's own datasource): INSERT and SELECT on the trail work; UPDATE, DELETE,
TRUNCATE, DROP TRIGGER, DISABLE TRIGGER, DROP TABLE, CREATE TABLE are refused for a missing privilege (SQLState
42501, not the trigger's P0001). Red first (7 of 8). `DatabaseCredentialsStartupTest` 44 cases (16 new, red first);
`compose-credentials.test.sh` 9 cases (2 new, red first). Mutations: no `REVOKE` on `audit_entries` turns UPDATE and
DELETE red; no same-user check turns its 2 cases red. The whole suite runs as `medlink_app`. Setup scripts, 0
failures: `setup-dev.ps1 -KeepData` on a pre-#57 volume, `setup-dev.sh --full-stack`, `setup-dev.ps1 -FullStack`.

**For whoever deploys:** the deploy secret must also hold `APP_DB_PASSWORD` (docker-compose.yml requires it); it is
the only new key (`docker-compose.yml` gives Flyway `POSTGRES_USER` / `POSTGRES_PASSWORD`). The deploy action copies the
role script to the server and runs it before the API starts, on every deploy. At first it did not copy it: a fresh
server could not have initialised its database, and an older one would have kept `medlink_app` without a login (found
after the PR opened). Proof: `DeployComposeActionTest` (red first), and the action's remote steps replayed on a
disposable compose project: fresh volume, a role left without login, a second run; `medlink_app` logs in each time. The
Terraform path creates the role by hand (`terraform/README.md`, "Database roles").

**Limits:**
- The API on the host (`mvnw spring-boot:run`, dev) still connects as the owner: Spring Boot's compose integration
  gives the container's user to both. The tests, the full stack, staging and prod use `medlink_app`.
- Flyway runs inside the API, so the API process holds the owner's password in its environment: an SQL injection
  runs as `medlink_app`, but code execution inside the API would reach the owner. Next step: migrate in a one-shot
  container and give the API only the runtime role.
- A later append-only table needs its own `REVOKE` (the default privileges grant DML).

## MLink-164 — no default database credentials outside dev, GitHub #64 (2026-11-11, fix/db-hardening)

Plan and step-by-step history: [db-hardening.md](db-hardening.md) (Part A).

**Built:**
- `application-prod.yml`, `application-staging.yml`: `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` with no default.
- `config/DatabaseCredentialsCheck` (staging and prod): a `BeanFactoryPostProcessor`, so it runs before Flyway and
  nothing connects with guessable credentials. It refuses a missing, empty or unresolved value, the dev values
  `myuser` / `secret`, and anything starting with `change-me` (any case: every placeholder of `.env*.example` and
  `terraform.tfvars*.example`). The message names the variable, never the value.
- `docker-compose.yml`: `${POSTGRES_DB|USER|PASSWORD:?...}`, so `docker compose` stops when one is missing. Dev keeps
  its fixed values (`compose.yaml`, `application-dev.yml`). It also passes `CORS_ALLOWED_ORIGINS`, `TRUSTED_PROXIES`
  and `KEYCLOAK_ADMIN_CLIENT_ID` to the API container, which it did not before (`ComposeFilesTest`).
- Examples (`.env*.example`, `terraform.tfvars*.example`) say the placeholders are refused.

**Why the check is needed:** removing the yml default alone does not fail closed. `spring.datasource.*` is bound as
configuration properties, and the binder keeps an unresolved `${SPRING_DATASOURCE_USERNAME}` as plain text: the API
would try to log in as that literal user and fail later with a misleading error.

**Proof:** `DatabaseCredentialsStartupTest` starts the real API under prod and staging with a fake process
environment and a closed database port (no Docker): 28 cases, committed red first each time (18 then 8 failures).
Mutation checks: a default back in prod's yml, and a case-sensitive prefix, each turn cases red.
`scripts/compose-credentials.test.sh` (CI step): 7 cases with an empty `--env-file`, so the developer's `.env` cannot
make it pass. Both setup scripts pass in full-stack mode (2026-11-11).

**For whoever deploys:** the deploy secret must hold `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` with real
values, or the deploy stops at `docker compose` (on purpose). Staging and prod tests that start a staging/prod
profile must pass `TestcontainersConfiguration.UNUSED_DB_CREDENTIAL`.

**Limits:** ~~Flyway and the API still share one database user, the owner of every table~~: closed by GitHub #57
(MLink-165, section above, 2026-11-17).

## MLink-31 — Accéder au dossier du patient (back-end)

**Tickets:** 31.1, 31.2, 31.3, 31.6 (story MLink-31, epic MLink-10). Front (31.4, 31.5) in `web-MedLink`.

**Route:** `GET /api/patients/{id}/record` → 200 `{"patient": {"id", "firstName", "lastName", "birthDate", "phone"}, "reports": []}`. Practitioners only; the practitioner is always the caller.

**Decisions** (agreed 2026-11-10, comment on 31.1)
- D1 — "attached" = the practitioner has a `BOOKED` appointment with the patient, past or upcoming (`appointments → slots.practitioner_id`). A cancelled appointment does not attach: the patient was never seen (secret médical). No new table, no migration (`AppointmentRepository.existsByPractitionerAndPatient`).
- D2 — not attached, unknown patient and an id that is not a UUID get the same 403 (`AccessDeniedException`, so the refusal is audited as `ACCESS_DENIED`); the link is checked before anything is read.
- D3 — `RECORD_VIEWED` (`SUCCESS`, resource `PATIENT_RECORD`, ids only) is written before the record is returned: a read that cannot be traced is not answered. `AuditService` writes in its own transaction.
- Identity only: no address, no email (31.2). `reports` stays empty until MLink-32 gives it its type.
- `SecurityConfig` checks the `PRACTITIONER` role for `GET /api/patients/*/record` before Spring reads the request (lesson of the review on #116); the class-level `@PreAuthorize` stays. The id is read as text, so nothing is converted before the role check.
- New enum values: `AuditAction.RECORD_VIEWED`, `AuditOutcome.SUCCESS` (#116 also adds `SUCCESS`: keep one at merge).

**Proofs**
- `PatientRecordFunctionalTest` (5 tests), committed red first (5 of 5 failing with 404), then green: attached practitioner 200 with identity only and `Cache-Control: no-store`; a past appointment attaches; not attached, cancelled only, unknown and malformed ids all get the same 403 and 4 `ACCESS_DENIED` rows, no `RECORD_VIEWED`; patient (even the one concerned) and staff 403, anonymous 401; two reads → two `RECORD_VIEWED` rows with the practitioner's id.
- Mutation checks: any appointment instead of `BOOKED` → `expected: 403 but was: 200`; without the audit call → `Expected size: 2 but was: 0`.
- Access matrix: one new line, `GET /api/patients/{id}/record` L,F,F,F.
- `./mvnw -B clean verify`: **484 tests, 0 failures**; reverse class order: 484 tests, 0 failures. One earlier run had one error that did not come back in the next runs.
- Postman folder 10 (access rules; the 200 needs a practitioner profile and an appointment, which no endpoint creates yet): 19 requests, 31 assertions, 0 failures. Whole collection: **151 requests, 345 assertions, 0 failures**, twice in a row, after `setup-dev.sh --reset`.

**Found on the way:** after switching branches, `./mvnw verify` without `clean` kept another branch's `V15__agenda_reference_data.sql` in `target/classes`, and `AppointmentSchemaTest.outsideTheDevProfileThereIsNoSeedData` failed locally (3 clinics). Not a code issue (CI starts from an empty `target/`): use `./mvnw clean verify` after a branch switch when migrations differ.

## MLink-65 — setup scripts check the session and the staff code (2026-10-31, updated 2026-11-07, feat/ss3-setup-staff-code-check)

- **Built:** `scripts/setup-dev.ps1` and `scripts/setup-dev.sh` (same behaviour, bash 3.2 safe). The `patient.dev`
  login check now calls `GET /api/auth/me` (#107): `[OK]` only for 200 with the `roles` array exactly `["PATIENT"]`. Then a fresh
  browser logs in as `practitioner.dev` with the right password: `[OK]` only when the login answers 200 with `"mfaRequired": true` **and** the same cookies get 401 on `/api/auth/me` (no
  session before the code); anything else is a warning. The cookie and answer files are deleted afterwards; the
  password is never printed. README and the scripts' header mention the check.
- **Proof:** on a fresh realm, `setup-dev.ps1 -SkipTests -Reset` (Windows PowerShell) and `setup-dev.sh --skip-tests`
  (Git Bash 5.2, volumes re-imported): **0 failures, 0 warnings**, both print `[OK] staff code asked`. Again after
  the switch to `/api/auth/me` (2026-11-07, `master` with #107 and #103 merged in): `setup-dev.ps1 -SkipTests` and
  `setup-dev.sh --skip-tests`, **0 warnings** each, both print the `PATIENT` role line and `[OK] staff code asked`.
- **2026-11-07:** the first version checked the session on `/api/users/_health`, because `/api/auth/me` was not on
  `master` yet; after #107 merged, both checks use it, as the ticket asks.
- **Review of #113 (2026-11-08):** the first `/api/auth/me` check searched the whole answer for `"PATIENT"`, so
  `PATIENT` plus a privileged role would still have passed. Both scripts now read the `roles` array (`tr`/`sed` in
  bash 3.2, `ConvertFrom-Json` in PowerShell) and want exactly `["PATIENT"]`. Checked offline on 8 fake answers in
  bash, Windows PowerShell 5.1 and PowerShell 7: only `["PATIENT"]` (compact or pretty-printed) passes; `PATIENT`
  with `ADMINISTRATIVE_STAFF` or `PRACTITIONER`, an empty array, no array, `patient` in lower case and a missing
  answer all fail. `master` merged in (52 commits). Live: `setup-dev.ps1 -SkipTests` and `setup-dev.sh --skip-tests`
  0 warnings each (`roles exactly ["PATIENT"]`, `staff code asked`); `./mvnw -B verify` 475/475 and reverse class
  order 475/475.
- **Found on the way:** after an API run started by `setup-dev.sh` (Git Bash on Windows), both `setup-dev.ps1 -Stop`
  and `setup-dev.sh --stop` stop the containers, but the API's Java process (forked by `spring-boot:run`) keeps port
  8080, so the next run stops at "Free these ports first". A run started by `setup-dev.ps1` stops cleanly. To report
  as an issue; not fixed here.

## MLink-62, MLink-63 - staff code, TOTP (2026-10-25, feat/ss3-staff-totp)

- **Contract** (plan.md §4, endpoints 5, 8, 9): for `PRACTITIONER` and `ADMINISTRATIVE_STAFF`, the password alone
  answers `200 {"mfaRequired":true,"mfaSetupRequired":true|false}` and opens no session; the accepted login waits
  5 minutes in the server session (`MfaPending`, Keycloak tokens included, never sent). `POST /api/auth/mfa/setup`
  → QR code (PNG data URI), otpauth URL, Base32 secret; 401 without the step; 409 `MFA_ALREADY_ENROLLED`.
  `POST /api/auth/mfa/verify` → `{user}` + session (new id, 15 idle minutes); 401 `INVALID_CODE`; 400 for a code
  that is not 6 digits (checked after the step, so a caller without one gets 401); 423 `MFA_LOCKED` from the 5th
  wrong code. Patients are unchanged.
- **Library choice:** RFC 6238 is a few lines of `javax.crypto` (HMAC-SHA1, dynamic truncation), so it is our own
  `service/Totp`, checked against the RFC's published vectors; ZXing `core` 3.5.3 (no transitive dependency)
  draws the QR code, the JDK writes the PNG. A TOTP library would add a dependency for less than 100 lines.
- **Decisions** (with Mohamed, recorded in [ss3-staff-totp.md](ss3-staff-totp.md)):
  - The secret is sealed with AES-256-GCM, a new IV each time, the user id as associated data (a secret copied to
    another row does not decrypt). Key `APP_MFA_ENCRYPTION_KEY` (32 bytes, base64), checked at start.
  - Wrong codes are counted per user in the database, not per pending step: 5 → the code step is locked for 15
    minutes, across logins and parallel requests (stronger than plan.md's first draft). The lock also drops the
    pending step and ends the Keycloak session. A good code resets the count.
  - A code works once (`last_used_step`), within one step of tolerance either side.
  - `repository/UserMfaStore` uses `JdbcClient`, not JPA: each rule is one SQL statement, atomic without a
    transaction (like `SessionRevocationStore`).
  - The forced password change and the code step share `security/PendingStep<T>` (5 minutes, old session dropped).
  - MFA audit entries come after #90 (Chourouk's `LOGIN` audit) merges; for staff, `LOGIN` SUCCESS should then be
    recorded after the code (proposed on #90). Logs name the user and the outcome only.
- **Proof:** `MfaFunctionalTest` 20 (every contract line, over HTTP, real Keycloak and database), `MfaServiceTest` 9,
  `UserMfaStoreTest` 15 (10 wrong codes at once: exactly 5 counted; one code twice at once: works once),
  `TotpTest` 27, `MfaSecretCipherTest` 14, `MfaKeyStartupTest` 3, `QrCodesTest` 3, `PendingStepTest` 7; the access
  matrix has the 2 routes (57 cells). Each rule was broken once on purpose and its test turned red. Full suite
  **349/349** in normal and reverse class order. Postman: folder 6's staff step now does the code; new folder 8
  (28 requests) on a disposable practitioner; newman **101 requests, 240 assertions, 0 failures**, twice in a row.
- **Tests:** in functional tests the API's `Clock` is real time shifted forward (`FunctionalTestBase.CLOCK`): each
  staff login uses the next 30-second code, and a test lets the 15-minute lock or the 5-minute step run out.
  `Browser.login()` does the code step for practitioners and staff, so teammates' tests keep working.
- **Found on the way:**
  - A full session opened after a pending step kept its 5-minute idle time; `LoginSessions.open` now sets the
    usual 15 minutes again (red test first).
  - The realm's `defaultGroups: ["/patients"]` put every console-made practitioner or staff member in `/patients`
    too: fixed in review round 1 below.
- **Review round 1 (code owner, 2026-10-28):**
  - **Role leak:** `defaultGroups` removed from `keycloak/realm-medlink.json` (registration adds `/patients`
    itself); the test helper no longer cleans up after the console. `ConsoleStaffAccountFunctionalTest`: a
    console-made practitioner and staff member hold their own group and role only; `RealmFileTest` keeps the
    default out. **Fail closed:** a login whose token holds more than one MedLink role gets 403
    `ACCOUNT_SETUP_INCOMPLETE`, no session, its Keycloak session ended, logged (3 two-group cases).
  - **First-enrolment takeover:** the password alone no longer enrols a code. A login with `mfaSetupRequired:
    true` mails a single-use link (`MfaEnrolmentLinks`, purpose `MFA_ENROLMENT`, **V12**, 30 minutes, at most one
    mail per 15 minutes, a "someone knows your password" warning); endpoint 8 needs its `{token}`, bound to the
    account (another staff member's valid link: 400 `INVALID_TOKEN`), and the first good code closes it.
    `MfaEnrolmentLinkFunctionalTest` (9, over HTTP) and `MfaEnrolmentLinksTest` (3: throttle, mail failure
    logged without the token). The TOTP rules (`Totp`, cipher, `user_mfa`, endpoint 9, the lock) are unchanged.
  - **SCA:** the gate was red on `master`'s baseline, not on ZXing (run 190 completed and flagged only
    devtools and DOMPurify): fixed for every PR in #98 (MLink-127).
  - Each new rule: a red commit, then green, then broken on purpose once (two-role limit, owner filter of the
    token, "send only when setup is needed", the token check, closing the link).
- **Limits:** changing `APP_MFA_ENCRYPTION_KEY` makes every enrolled code unreadable: verify answers 500 and the
  log names the user and the likely cause, never the secret; re-enrolment would come with an admin reset (later
  ticket). A pending step that runs out leaves its Keycloak
  session to Keycloak's idle timeout. No second factor for patients (not planned).
- **Before merging:** add `APP_MFA_ENCRYPTION_KEY` (`openssl rand -base64 32`, one per environment) to the
  staging and prod `DEPLOY_ENV_FILE`; compose refuses to start without it. Local `.env`: the setup scripts add it.
  In the staging and prod Keycloak consoles, remove the default group `/patients` and take `/patients` off the
  existing practitioners and staff (`docs/DEPLOYMENT.md`); locally, `setup-dev -Reset` re-imports the realm.

## MLink-122 — metrics not public in production, GitHub #49 (2026-10-25, fix/49-management-port)

Design agreed with the code owner on #49 (a separate management port, not authentication).

**Built:**
- `application-staging.yml`, `application-prod.yml`: `management.server.port: 9000`. All of Actuator lives there; the
  public port no longer serves `/actuator/**`. Port 9000 is never published: Prometheus scrapes `app:9000` and
  `app-staging:9000` over the Docker network (`monitoring/prometheus/prometheus.yml`).
- `application.properties` (every profile): `/livez` and `/readyz` on the main port
  (`management.endpoint.health.probes.*`); they answer `{"status":"UP"}` and nothing else.
- `SecurityConfig` (shared file): `GET /livez` and `/readyz` public. The `/actuator/prometheus` rule stays: Spring
  Security also guards the management port, so Prometheus (no login) needs it there; what keeps the metrics private
  in staging and prod is the unpublished port. Mutation check: without the rule, Prometheus gets 401 on port 9000.
- Health checks → `/readyz`: `docker/Dockerfile` `HEALTHCHECK`, `terraform/main.tf` healthcheck and its README,
  the deploy-compose action and `docs/DEPLOYMENT.md` (with a "never publish 9000" security note), as the code owner
  asked on #49. Dev (setup scripts, DAST with the dev profile) keeps Actuator on 8080.

**Proof:** `ManagementPortIntegrationTest` (real API, real profile files): staging and prod, `/actuator/prometheus`
and `/actuator/health` on the main port refused to anonymous callers, metrics served on the management port,
`/livez` and `/readyz` UP on the main port with no detail; dev keeps metrics and health on the main port and gains
`/readyz`. The management port is random there, so `ManagementPortConfigTest` reads the files: staging and prod set
9000, every Prometheus target uses it, no compose file publishes it. Committed red first (9 failures), then green.
`scripts/ci/check-prod-config.sh` passes.

**For whoever deploys:** set the GitHub Environment variable `DEPLOY_HEALTH_URL` to the API's `/readyz` URL in
`staging` and `production`. A proxy must not route port 9000.

**Code owner's review on #94:** the default readiness group holds only `readinessState`, so with every health check
moved to `/readyz` a database outage still looked healthy. Now `management.endpoint.health.group.readiness.include =
readinessState,db` (every profile); `/livez` stays the process only, so a database outage never restarts the API.
`ReadinessProbeIntegrationTest` (prod profile) stops its own Postgres container: `/readyz` 200 → **503 DOWN** (no
detail), `/livez` still 200 UP. Committed red first (33b634d: `/readyz` still 200 without the database), then green
(297a5ef).

**Limits:** Swagger and `/v3/api-docs` are not Actuator and stay public in production: GitHub #65.

## MLink-91, MLink-88, MLink-90 - password changes (2026-10-23, feat/ss4-password-change, [#89](https://github.com/assali-mohamed-ali/api-MedLink/pull/89))

- **MLink-91:** `POST /api/auth/password` changes the logged-in user's password. The current password
  is checked by a Keycloak password grant, then `KeycloakAdminService.setPassword` applies the new one;
  every session of the user then ends (see the review below). Wrong current password -> `401 INVALID_CURRENT_PASSWORD`;
  Keycloak policy -> `400 PASSWORD_POLICY` with the existing French messages. No password or token is
  logged; the browser keeps only its session cookie.
- **MLink-88:** `POST /api/admin/users/{id}/force-password-reset` is staff-only (`204`, unknown id
  `404`). It adds Keycloak's `UPDATE_PASSWORD` and ends the user's Keycloak sessions. The next password
  login returns `200 {"passwordChangeRequired":true}` in a five-minute pending session that carries no
  authenticated principal. `POST /api/auth/password` then needs only the new password and is single-use.
  A normal login clears the pending marker.
- **MLink-90:** the two routes are in `AccessMatrixFunctionalTest` (anonymous 401, patient/practitioner
  403, staff allowed; a missing pending session is 401). The forced test also covers a weak forced
  password (pending stays usable), the single-use rule, session shutdown and a normal login afterwards.
- **Proof:** `PasswordChangeFunctionalTest` 5/5 green; `ForcePasswordResetFunctionalTest` 3/3 green;
  full suite 222/222 in normal and reverse class order. Postman folder 6 (66 requests, 176 assertions,
  0 failure) runs the complete scenario: normal change, wrong current, policy refusal, staff reset,
  pending login, forced change, reuse refusal.
- **Known scope:** only Keycloak sessions are ended by the reset. API sessions end at their next token
  renewal through `SessionValidityFilter`; the access token lifespan is five minutes.
- **Code owner's review (2026-10-23).** Scope: endpoint 10 is a password change (with a session, or the
  pending step after a staff reset), not forgot password, which stays out of scope (plan.md §8); a
  comment on the PR had said otherwise. Keycloak 26.8.1 checked on the dev stack first: "Account is not
  fully set up" comes only after the right password, for any required action and for an incomplete
  profile; `reset-password` with `temporary=false` drops `UPDATE_PASSWORD` itself and keeps the other
  actions. Changes:
  - Another required action next to `UPDATE_PASSWORD` (or none, an incomplete profile): the login
    answers `403 ACCOUNT_SETUP_INCOMPLETE` before any change, logged for an admin. Only the password's
    owner sees it, as with `ACCOUNT_DISABLED`.
  - The forced change is one Keycloak write (`clearPasswordChange` removed): nothing left half done.
  - The staff reset blocks new logins first, then refuses the old API sessions, then ends Keycloak's;
    every step is idempotent, so an error means "send it again" and nothing is rolled back.
  - A normal change ends every session (decided with the lead). Fail closed, whatever Keycloak does:
    `V9__session_revocations.sql` adds one mark per user, set *before* the password changes;
    `SessionValidityFilter` refuses any session opened at or before it, on every call (one primary-key
    read). A password Keycloak refused puts the mark back (a weak attempt logs no one out); no answer
    keeps it (the password may have changed). Keycloak's sign-out still runs; if only it fails, the
    leftover Keycloak sessions are unusable (the browser never holds Keycloak's tokens) and a warning is
    logged. A separate table, not a `users` column: a login saving the users row never writes an old
    mark back. A reset landing in a still-fresh session answers `401 SESSION_EXPIRED` instead of a 500.
  - Only Keycloak's exact wording opens the forced change; another wording fails closed as `401`.
  - Proof: new functional tests (every session ends; an old session refused while Keycloak still
    accepts it; another action refused; a reset sent twice), `SessionRevocationStoreTest` and unit tests
    for the Keycloak failures between two calls, each committed red first; Postman folder 6
    checks that the change ends the session (67 requests, 178 assertions, 0 failure); full suite 243/243 in
    normal and reverse class order.

## MLink-87 - password history (2026-10-22, feat/ss4-password-history)

- Realm `passwordPolicy` gains `passwordHistory(3)`: a new password must differ from the last three.
- No migration, no endpoint, no Postman change; Keycloak remains the only place that stores passwords.
- The French message was already mapped in `InvalidPasswordException`
  (`invalidPasswordHistoryMessage`): "Le mot de passe doit être différent de vos 3 derniers mots de passe."
- **Proof:** `PasswordHistoryFunctionalTest` creates a user and sets two passwords through
  `KeycloakAdminService`, then reuses the first: Keycloak refuses it and the API raises
  `PASSWORD_POLICY` with that French message. Red first (no throwable), then green.
- **Every dev machine re-imports the realm once:** the setup script sees the realm file hash change and
  resets the volumes automatically; `-Reset` does the same by hand.

### Where we are (handoff, 2026-10-16)

Merged on `master`: sub-sprint 1 (#32), staff-only `/api/users` (#33, MLink-57), GitHub issues #28/#29
(#35, #34), CI fix (#37), realm: lockout, event log, 15-minute idle, password grant (#38: MLink-66, 67, 83),
handoff docs (#40), **login through the API (#41: MLink-55, MLink-65 part)**, Swagger CSRF header (#42).
Issues #27–#31 are closed.

**PR #39 did not reach `master`:** it was stacked on #38's branch and merged into it after #38 had gone to
`master`. It is landed by the PR `fix/land-pr-39` (code owner's review on #32: no account enumeration on
register, MLink-79; `Patient extends User`, MLink-78; the provisioning race fix; Mailpit functional tests;
Postman). Lesson: **no stacked PRs, always base on `master`** (CLAUDE.md, Git).

- Still open on #32: review comment 2 ("disable instead of delete" for a failed sign-up), waiting for the
  code owner's answer on the trade-off.
- Ask the repo admin: make `build-test` a required check in the ruleset; delete merged remote branches and
  turn on auto-delete.

**Sub-sprint 2 split** (agreed on MLink-55):
- **Youssef:** MLink-55 done (#41). In progress: MLink-54 logout, MLink-65 (setup scripts' login check).
  Then MLink-86 session expiry test.
- **Mohamed:** MLink-66, 67, 83 done (#38). MLink-78, 79 in review (landing PR). MLink-84 cookie hardening
  is unblocked now that the redirect login is gone; it touches the session config next to Youssef's
  MLink-54, so say on MLink-54 which files change before starting.
- **Ahlem:** React pages, MLink-68 scripted lockout check (attempts more than 1 s apart, see the realm
  section), MLink-53 `/me`.

**Next tickets for Mohamed** (his own, in this order):
1. MLink-87 password policy (history + French messages). It changes the realm file, so everyone's dev
   realm is re-imported once.
2. MLink-84 cookie hardening: in review on `feat/ss2-session-cookie-hardening` (2026-10-19), see its section.
3. Sub-sprint 3 (staff code): MLink-62, MLink-63.
4. Sub-sprint 4 (passwords): MLink-91, MLink-88, MLink-90.

## Where the code stands (start of sprint 2)

- Merged on `master` (PR #22): roles `PATIENT` / `PRACTITIONER` / `ADMINISTRATIVE_STAFF`;
  `users.id` = Keycloak id (V3); controller maps through `mapper/UserMapper`, service returns
  entities; one-command setup scripts for Windows and macOS/Linux with automatic realm
  re-import and realm backup; Postgres pinned to 16.
- Still from the earlier design, replaced in sub-sprint 2: login by redirect to Keycloak
  (`oauth2Login` in `config/SecurityConfig`), dev users' rows created nowhere yet.
- On the lead's machine the local database applied an earlier V3: run the setup script with
  `-Reset` once.

## MLink-167 — one appointment at a time per patient, 409 `PATIENT_BUSY` (2026-11-18, epic/MLink-8)

Found in a demo: one patient booked two appointments on the same day at 08:00, one in Sousse and one in Sfax. No rule
stopped it, because `SLOT_TAKEN` only guards one slot.

**Rule.** A booking (`POST /api/appointments`) or a move (`POST /api/appointments/{id}/reschedule`) onto a slot that
overlaps one of the patient's `BOOKED` appointments answers **409** with code `PATIENT_BUSY` and the message
"Vous avez déjà un rendez-vous à ce moment-là." Two times overlap when one starts before the other ends: back-to-back
slots are fine. A cancelled appointment does not count. For a move, the appointment being moved does not count either,
because it is cancelled in the same transaction before the new one is checked. The rule is per patient: another
patient can book at the same time.

**Design.**
- `AppointmentRepository.existsOverlapping(patientId, status, start, end)` is checked in `bookLocked()`, after the
  slot's FREE check. Booking and moving both go through it.
- Two sessions of one patient could book two overlapping slots at the same instant: each slot has its own lock, so the
  check alone would let both pass. `PatientRepository.lockForBooking` (`select id from patients where id = ? for
  update`) now serialises one patient's bookings and moves. `book()` takes it first; `reschedule()` takes it before
  locking the slots, in the same order, so no deadlock. An unknown patient row still answers 403
  `PATIENT_PROFILE_REQUIRED`.
- Other patients are not slowed down, because each patient has their own lock.

**Tests.** `PatientOverlapFunctionalTest` (7 tests, real API, Keycloak and database):
- the same time in another clinic is refused, and the second slot stays FREE;
- a partial overlap is refused;
- back-to-back slots and another patient at the same time are fine;
- a cancelled appointment does not block its time;
- a move onto another appointment's time is refused, and the old appointment is kept;
- a move that overlaps only the moved appointment is fine;
- two sessions booking overlapping slots at the same instant give `[201, 409]` in each of 5 rounds.

`AppointmentAuditAtomicityTest` booked one patient three times at the same hour, which the new rule forbids. It now
uses distinct hours (74ed232).

Postman, folder 7: the collection now picks a slot that fits around the patient's appointments, then books a slot at
the time of an existing appointment and expects 409 `PATIENT_BUSY`. That request is skipped when the dev seed has no
such slot.

**Proof** (Ubuntu VM with Docker):
- Red e71b60f, with the previous code: 4 of 7 fail. The three that pass guard what was already allowed.
- Green 2d7026f: 7/7, and 27 tests with the appointment tests run alongside, 0 failures.
- Mutation: without the `existsOverlapping` check, 4 of 7 turn red again.
- On 74ed232: `./mvnw -B verify` **752 tests, 0 failures**; reverse order 752, 0 failures.
- `setup-dev --skip-tests` then newman: **208 requests, 438 assertions, 0 failures**.
- After `master` (6cf2a6c) merged in for the code owner's review on #128 (bfb3ca0; only `report.md` conflicted, both
  sides kept; `AppointmentService` now also calls the MLink-37 alert after the audit lines): `./mvnw -B clean verify`
  **887 tests, 0 failures**; reverse order 887, 0 failures; newman **230 requests, 478 assertions, 0 failures**.

## Review on #120 — booking audit, reminder vs cancel, at-least-once reminders (2026-11-15, epic/MLink-8)

The code owner kept *changes requested* on #120 for three reliability findings (the Trivy CVE of 2026-11-11 was fixed
on `master` meanwhile). Each fix: red test commit, green commit, then a mutation check.

**1. A booking and its audit row are one unit (MLink-104).** Before, `AppointmentController` wrote
`APPOINTMENT_BOOKED` with `AuditService.record` (`REQUIRES_NEW`) after `book()` had committed: a failed audit write
answered 500 for a slot that stayed booked, and the patient's retry met `SLOT_TAKEN`. Now `AppointmentService.book`
writes the row with `AuditService.recordWithin` (`MANDATORY`, added by #116 for the same review on the agenda) inside
the booking transaction; the controller no longer audits.
- Test `AppointmentAuditAtomicityTest` (service, Testcontainers, audit repository made to fail as in
  `AgendaAuditAtomicityTest`): a failed audit leaves no appointment and a FREE slot; a booking writes exactly one
  `APPOINTMENT_BOOKED` row. Red with the previous code (609f323, checked with the code of the commit before): 2 of 2
  fail. Green (eea5401): 15 tests with the booking and audit functional tests, 0 failures.
- Mutation note: swapping `recordWithin` for `record` *inside* the transaction does not turn it red (the audit
  failure still propagates and rolls the booking back); the guarded defect is the audit written after the commit,
  which is exactly what the red run proves.

**2. A reminder never crosses a cancel (MLink-110).** Before, the job read the due appointments once and sent from
that detached list: a cancel during the run still got its reminder, and the marker update did not check the status.
Now `findRemindersDue` returns ids only; for each one, a short transaction locks the row (`findForChangeById`, the
lock a cancel takes), checks it again (still `BOOKED`, `reminder_sent_at` empty, still in the window), sends, and sets
`reminder_sent_at` on that locked row. A cancel and a reminder run one after the other; a second API instance waits
on the lock and then finds the reminder marked. `markReminderSent` is removed. The lock is held at most one SMTP
attempt (timeouts 5 s connect, 10 s read/write); retries release it between attempts.
- Test `AppointmentReminderRaceTest` (service, Testcontainers, mail sender spied so nothing is sent): the second due
  appointment is cancelled while the first one's mail is being sent; it gets no reminder and stays unmarked.

**3. Delivery is at least once, and said so (MLink-111).** SMTP cannot join the database transaction: if the mail
server accepts a reminder and the marker then fails to commit (crash, database), the next run sends it again. That
is now the documented contract, logged as "sent but not marked", and both copies carry the same
`Message-ID: <appointment-reminder-{appointmentId}@medlink.local>` (`MailService.reminderMessageId`), so mail systems
can recognise the duplicate.
- `AppointmentReminderJobTest` (unit, 5 tests): outage → 3 attempts, logged by id only, nothing committed; server back
  → sent and marked; one failure then success; **sent but marker commit fails → sent again next run, same
  appointment**; found cancelled when locked → no mail. `AppointmentReminderFunctionalTest` checks the Message-ID in
  Mailpit.

**4. A cancel and a move are audited too (MLink-106, MLink-107), round 3 of the review.** A move created its new
appointment through `bookLocked()` with no audit row, and a cancel changed the appointment with none. Now `cancel()`
writes `APPOINTMENT_CANCELLED` (resource: the cancelled appointment) and `reschedule()` writes `APPOINTMENT_RESCHEDULED`
(resource: the new appointment), both with `recordWithin` in the change's transaction; a cancel of an already
cancelled appointment changes nothing and writes no row. `AppointmentAuditAtomicityTest` (+3): a cancel whose audit
fails leaves the appointment BOOKED and its slot taken; a move whose audit fails keeps the old appointment and leaves
the new slot free with no appointment; a move and a cancel (twice) write exactly one row each. Red b49c4ef (the events
are matched by name, so the test compiles on the previous code), green cab279b. The cancellation alert of MLink-37
(#125, Ahlem) will be called right after these audit lines, for a cancel and for the old appointment of a move.
Proof on b3b8640 (after `master` with #93 merged in): red 3 of 5 failing with the previous `AppointmentService`;
green 5/5, with the cancel and move functional tests (21, 0 failures); `./mvnw -B verify` **745 tests, 0 failures**,
reverse order 745, 0 failures; newman **204 requests, 431 assertions, 0 failures**.

**Proof** (Ubuntu VM with Docker):
- Red (4286db7, run with the code of the commit before): `AppointmentReminderRaceTest` fails (the cancelled one got
  its mail) and the Mailpit test fails (no stable Message-ID). Green (f79fd9c): 10 reminder tests, 0 failures.
- Mutation (fix 2): without the `BOOKED` recheck under the lock, `AppointmentReminderRaceTest` turns red.
- `./mvnw -B verify` **639 tests, 0 failures**; reverse order 639, 0 failures (one earlier run had `AgendaFunctionalTest`
  miss its staff-code mail while `setup-dev` ran in parallel; alone it passed, 22/22, then the full run was green).
- `setup-dev --skip-tests` then newman: **191 requests, 413 assertions, 0 failures** (after #116 and #117 merged in).

## MLink-111 — one reminder only, retried and logged on failure (2026-11-08, epic/MLink-8)

Task 36.2 of MLink-36: "un échec d'envoi est réessayé et consigné", and never two reminders.

**Built** (in `AppointmentReminderJob`, no new dependency):
- A failed mail (`MailException`) is tried 3 times in the run, waiting `app.appointments.reminder-retry-backoff` (2 s),
  then twice that. After the third failure: one `WARN` line with the appointment id only (never the address, the
  practitioner or the clinic), `reminder_sent_at` stays empty, and the next run (15 minutes later) tries again.
- `reminder_sent_at` is set only after a sent mail (MLink-110), and the job's query skips rows where it is set: two runs
  send one mail. If the application stops during a wait, the reminder is left for the next start.

**Proof:**
- `AppointmentReminderFunctionalTest.theJobRunningTwiceSendsOneReminderOnly` (Mailpit): two runs, one mail,
  `reminder_sent_at` unchanged by the second.
- `AppointmentReminderJobTest` (unit, mocked mail server, the outage a functional test cannot cause on demand): down →
  3 attempts, the failure logged without the address, `markReminderSent` never called; server back → the next run sends
  and marks it; one failure then success → sent at the second attempt.
- Red first (the tests alone): they do not compile without the backoff in the job's constructor.
- Mutation check (the ticket's): with `reminder_sent_at` set before sending, the outage test and the "server back" test
  turn red (3 tests, 2 failures).
- `./mvnw -B verify` **519 tests, 0 failures**; reverse order 519, 0 failures. Postman: no endpoint changed.

## MLink-110 — reminder mail before an appointment, scheduled job (2026-11-08, epic/MLink-8)

Task 36.1 of MLink-36: a patient gets a reminder before their appointment.

**Rule:** every 15 minutes (`app.appointments.reminder-interval`), each `BOOKED` appointment that starts within the next
H-02 hours (`app.appointments.reminder-hours-before=24`, UTC, the shared `Clock`) and has no `reminder_sent_at` gets one
mail. A cancelled appointment, or one further away, gets none. No new route, no migration (`reminder_sent_at` is in V13).

**Built:**
- `AppointmentReminderJob.sendDueReminders()` (`@Scheduled`, first run one interval after start so a restart never sends
  a burst; `synchronized` so two runs never overlap): `AppointmentRepository.findRemindersDue` (slot, clinic and
  practitioner in one query), the holders' `patients` rows in one `findAllById`, the mail, then
  `markReminderSent` (only when still empty). A failed mail (`MailException`) is logged with the appointment id only and
  `reminder_sent_at` stays empty: the next run tries again. Retries with backoff are MLink-111.
- `MailService.sendAppointmentReminder(to, firstName, clinic, practitioner, startsAt)`: French, text + HTML, subject
  "Rappel de votre rendez-vous MedLink"; date and time in Tunisia's time (`Africa/Tunis`), practitioner, clinic, and a
  link to "Mes rendez-vous" to cancel or move it. No medical detail.
- `@EnableScheduling` was already on `config/RateLimitConfig` (MLink-112): no configuration file changed.
- One API instance today; several would need a lock on the job (out of this task).

**Proof** (Ubuntu VM with Docker): `AppointmentReminderFunctionalTest`, 3 tests calling the job directly, mails read in
Mailpit: booked within 24 h → one mail with date, time, practitioner and clinic, `reminder_sent_at` set; cancelled → no
mail; 30 h away → no mail.
- Red first (a90a02e, the test alone): it does not compile without the job.
- Mutation check (the ticket's): without the `BOOKED` filter, `aCancelledAppointmentGetsNoReminder` turns red (3 tests,
  1 failure). Green (e93f2dc): 3 tests, 0 failures.
- `./mvnw -B verify` **515 tests, 0 failures**; reverse order 515, 0 failures.
- Postman: no endpoint changed.

## MLink-107 — move an appointment, `POST /api/appointments/{id}/reschedule` (2026-11-08, epic/MLink-8)

Task 28.2 of MLink-28: a patient moves their appointment to another free slot, without losing it if that slot is taken.

**Contract:** session and `X-XSRF-TOKEN` required, PATIENT only. `POST /api/appointments/{id}/reschedule { "newSlotId" }`
→ 200, the new appointment (MLink-102's body). 400 `VALIDATION_FAILED` (`newSlotId` missing: "Le nouveau créneau est
obligatoire.", not a UUID: "Identifiant invalide.") · 401 · 403 `FORBIDDEN` (MLink-106's cases: someone else's, unknown
or unreadable `{id}`, a non-patient, no CSRF header) · 404 unknown new slot · 409 `SLOT_TAKEN` · 409
`APPOINTMENT_CANCELLED` "Ce rendez-vous est annulé : réservez un nouveau créneau." (not in the ticket: a cancelled
appointment has nothing to move) · 422 `CANCEL_DEADLINE_PASSED` (H-03 on the current appointment).

**Built:**
- `AppointmentService.reschedule`, one transaction: the appointment locked (unknown → the shared 403),
  `OwnershipGuard`, a cancelled one → 409; both slots locked in one order, lower id first, so two crossed moves never
  wait on each other (no deadlock); H-03 on the old slot; the old appointment is cancelled and its slot freed, then the
  new slot is booked (`bookLocked`, shared with `book`: FREE and not started, or 409; the unique index as last guard).
  Any refusal after the first change rolls the whole move back: the patient keeps the appointment they had.
- `AppointmentController.reschedule`: `{id}` read as for a cancel (unreadable → 403), then `newSlotId` (400). For a
  patient the body is checked before the appointment, which tells nothing about `{id}`: hence the access matrix line
  `POST /api/appointments/{id}/reschedule` (anonymous 401, patient past security with its 400, practitioner and staff
  403 from the filter chain). Path and body ids are read by two shared helpers (`appointmentId`, `slotId`).
- Postman folder 7: book a slot two days ahead, move it to the next one (200), my appointments shows the old one
  CANCELLED and the new one BOOKED, moving the cancelled one → 409 `APPOINTMENT_CANCELLED`.

**Proof** (Ubuntu VM with Docker):
- Red first (the tests alone; checked with the code of the commit before): `RescheduleAppointmentFunctionalTest` 9 tests,
  6 failures.
- Green (a1fadb7): with the access matrix, 94 tests, 0 failures.
- Mutation check (the ticket's): without `@Transactional` on `reschedule`, 6 tests turn red, among them
  `whenTheNewSlotIsTakenTheOldAppointmentIsKeptAsItWas`.
- `./mvnw -B clean verify` **512 tests, 0 failures**; reverse order 512, 0 failures.
- `setup-dev --skip-tests` then newman: **146 requests, 341 assertions, 0 failures**.

## MLink-106 — cancel an appointment, `POST /api/appointments/{id}/cancel` (2026-11-08, epic/MLink-8)

Task 28.1 of MLink-28: a patient cancels their own appointment, within the allowed time.

**Contract:** session and `X-XSRF-TOKEN` required, PATIENT only. `POST /api/appointments/{id}/cancel` → 204. 401
`UNAUTHENTICATED` · 403 `FORBIDDEN`, one answer for someone else's appointment, an unknown id, an unreadable id, a
non-patient or a missing CSRF header (never tell whether an appointment exists) · 422 `CANCEL_DEADLINE_PASSED` "Ce
rendez-vous ne peut plus être annulé moins de 12 heures avant son début." An already cancelled appointment: 204, nothing
changes.

**Built:**
- `AppointmentController.cancel` (`@PostMapping("/{id}/cancel")`, `@PreAuthorize` PATIENT, so administrative staff,
  whom `OwnershipGuard` lets through, cannot cancel here: the story says the holder only). `{id}` is text; an
  unreadable one is refused with the same 403 as an unknown one.
- `AppointmentService.cancel`, one transaction: `AppointmentRepository.findForChangeById` locks the appointment (an
  unknown id → `AccessDeniedException`, the shared 403), `OwnershipGuard.check(patientId)`, a CANCELLED appointment is
  left as it is (even if its slot has been booked again since), then the slot is locked (`findForBookingById`, as for
  a booking); less than the H-03 deadline before the start → 422; otherwise the appointment becomes CANCELLED with
  `cancelled_at` and the slot FREE.
- H-03 is `app.appointments.cancel-deadline-hours=12` (`application.properties`), computed on the server in UTC.
- `SecurityConfig` (shared): the PATIENT rule of the filter chain now covers `POST /api/appointments` and
  `/api/appointments/**`, so a path or body Spring cannot read never gives a non-patient anything but 403.
- `AccessMatrixFunctionalTest`: one line, `POST /api/appointments/{id}/cancel` (anonymous 401, and 403 for every role:
  `{id}` is a random id, someone else's). Postman folder 7: book a slot two days ahead, cancel it (204), it shows
  CANCELLED in my appointments, the slot is offered again, cancel again (204), an unknown id (403).

**Proof** (Ubuntu VM with Docker):
- Red first (the tests alone; checked with the code of the commit before): `CancelAppointmentFunctionalTest` 7 tests,
  5 failures (the route did not exist; anonymous 401 and the missing-CSRF 403 already held).
- Green (596fb4f): with the access matrix, 88 tests, 0 failures.
- Mutation check (the ticket's): without `ownership.check`, `anotherPatientAnUnknownIdAndAnUnreadableIdGetTheSame403…`
  turns red.
- `./mvnw -B clean verify` **499 tests, 0 failures**. Reverse order: once `QrCodesTest.scanningTheQrCodeGivesBackTheUrl`
  errored (ZXing `NotFoundException`, MLink-62/63, not touched here); it passed three times alone, and the reverse
  order run again gave **499, 0 failures**: a flaky decoding test, reported on its ticket.
- `setup-dev --skip-tests` then newman: **141 requests, 331 assertions, 0 failures**.

## MLink-108 — my appointments, `GET /api/appointments/me` (2026-11-08, epic/MLink-8)

Task 28.3 of MLink-28: a patient sees their own appointments, to cancel or reschedule them (28.1, 28.2, 28.4).

**Contract:** session required, PATIENT only. `GET /api/appointments/me?page=0&size=20` → 200 `{ "items": [ { "id",
"status", "clinic": { "id", "name" }, "practitioner": { "id", "firstName", "lastName" }, "startsAt", "endsAt" } ],
"total" }`, latest first (`startsAt` descending, then id), cancelled appointments included. 400 `VALIDATION_FAILED`
for `page` below 0 or `size` outside 1–50 (the slot search's messages) · 401 · 403 for a non-patient. No id in the URL:
always the caller's appointments.

**Built:**
- `AppointmentController.mine` (`@GetMapping("/me")`, `@PreAuthorize` PATIENT): `page` and `size` arrive as text and
  are read after the role check (a non-patient gets 403 even with `size=abc`); the patient is `currentUser.id()`.
- `AppointmentService.mine` → `AppointmentRepository.findByPatientId` with `@EntityGraph({"slot", "slot.clinic",
  "slot.practitioner"})` (one query, no N+1), sorted by `slot.startsAt` then id, descending. `AppointmentMapper` →
  `AppointmentPageResponse` (items are MLink-102's `AppointmentDto`). A patient without a profile simply has none.
- `AccessMatrixFunctionalTest`: one line, `GET /api/appointments/me` (anonymous 401, patient open, practitioner and
  staff 403). Postman folder 7: the 201 booking keeps the appointment id; a new request checks the demo patient's list
  holds it, latest first.

**Proof** (Ubuntu VM with Docker):
- Red first (dcd1b27; checked with the code of the commit before): `MyAppointmentsFunctionalTest` 6 tests, 4 failures
  and 1 error (the route did not exist; the anonymous 401 already held). Tests: patient A sees only A's, never B's,
  latest first, the cancelled one included; paging and `total`; an empty list; `size=51` and `page=-1` → 400;
  anonymous 401; practitioner and staff 403.
- Green (12596de): with the access matrix, 83 tests, 0 failures.
- `./mvnw -B clean verify` **488 tests, 0 failures**; reverse order 488, 0 failures.
- `setup-dev --skip-tests` then newman: **133 requests, 319 assertions, 0 failures**.

## MLink-104 — every booking in the audit trail (2026-11-08, epic/MLink-8)

Task 27.3 of MLink-27: each booking leaves a trace in the shared audit trail (MLink-59).

**Built:**
- `AuditAction.APPOINTMENT_BOOKED` and `AuditOutcome.SUCCESS` (MLink-114 and epic MLink-9 add `SUCCESS` on their
  branches too: a one-line conflict to keep once when the branches meet).
- `AppointmentController.book` calls `AuditService.record(APPOINTMENT_BOOKED, "APPOINTMENT", <appointment id>, SUCCESS)`
  once `AppointmentService.book` has returned, so the booking is committed: a refused (409, 404, 403) or rolled-back
  booking writes no SUCCESS. The line holds ids only: the caller (from the session, in `AuditService`), the appointment
  id and the client IP; never a name or a medical detail.
- `AppointmentAuditFunctionalTest` (real API): a booking writes exactly one `APPOINTMENT_BOOKED` line with the
  caller, `APPOINTMENT`, the appointment id, SUCCESS and an IP; a booking refused with 409 writes no SUCCESS line.

**Proof** (Ubuntu VM with Docker):
- Red first (94941ad, the tests alone; checked with the code of the commit before): 2 tests, 1 failure, the
  one-line test (no audit line). The refused-booking test already held: without an audit call there is no SUCCESS.
- Green (49e2d73); `./mvnw -B clean verify` **478 tests, 0 failures**; reverse order 478, 0 failures. No endpoint
  changed.

## MLink-103 — one booking per slot under concurrency, 409 `SLOT_TAKEN` (2026-11-07, epic/MLink-8)

Task 27.2 of MLink-27, its acceptance criterion: "if two patients choose the same slot, one appointment is made and the
other is told".

**Built:**
- No migration: the unique index the ticket asked for, `appointments_one_active_per_slot ON appointments (slot_id)
  WHERE status = 'BOOKED'`, is already in V13 (code owner's review of MLink-97).
- Two guards. First, the slot row lock of MLink-102 (`findForBookingById`, `SELECT … FOR UPDATE`) serves two bookings
  one after the other, and the second sees the slot taken. Second, the unique index: `AppointmentService.book` saves
  with `saveAndFlush`, and a violation of `appointments_one_active_per_slot` now answers the same 409 `SLOT_TAKEN`
  ("Ce créneau n'est plus disponible. Choisissez-en un autre.") instead of a 500; any other integrity error still
  fails as before (never hidden).
- `AppointmentRaceFunctionalTest` (real API): two patients post the same slot at the same instant (two threads
  released together), five rounds: each round one 201, one 409 `SLOT_TAKEN`, one BOOKED row. And the race lost after
  the checks (the slot still FREE with an active appointment already in the table): 409, not 500, nothing added.

**Proof** (Ubuntu VM with Docker):
- Red first (9a9b7eb, the tests alone): 2 tests, 1 failure, the last-guard test (expected 409 but was 500). The
  concurrent test was already green: the lock of MLink-102 holds; it proves the acceptance criterion.
- Green (663b46d): both race tests and `AppointmentBookingFunctionalTest`, 12 tests, 0 failures.
- Mutation check: when the translation no longer recognises the index, the last-guard test turns red again (500).
- `./mvnw -B clean verify` **475 tests, 0 failures**; reverse order 475, 0 failures. No endpoint changed: Postman as
  for MLink-102.

## MLink-102 — book an appointment, `POST /api/appointments` (2026-11-07, epic/MLink-8)

Task 27.1 of MLink-27: a patient books a free slot online and sees the confirmation.

**Code owner's post-merge review on #87 (2026-11-08): the role before the body.** Reading `slotId` as text after the
role check (below) is not enough: a body Spring cannot parse at all (`{"slotId":`, `not json`, `[]`) failed during the
`@RequestBody` conversion, before the method-level `@PreAuthorize`, so a practitioner or staff member got the parsing's
400 instead of 403. `SecurityConfig` (shared file) now makes `POST /api/appointments` PATIENT-only in the filter chain,
before the body is read; the method keeps its `@PreAuthorize`. New test
`aPractitionerAndStaffAreRefusedEvenWithMalformedJson`. Proof: red first (c785ea4; with the previous `SecurityConfig`,
1 failure, expected 403 but was 400, which is also the mutation check), green (137f7f8); `./mvnw -B clean verify`
**476 tests, 0 failures**, reverse order 476, 0 failures.

**Contract:** session and `X-XSRF-TOKEN` required, PATIENT only. `POST /api/appointments { "slotId" }` → 201
`{ "id", "status": "BOOKED", "clinic": { "id", "name" }, "practitioner": { "id", "firstName", "lastName" },
"startsAt", "endsAt" }`. 400 `VALIDATION_FAILED` (`slotId` missing: "Le créneau est obligatoire.", not a UUID:
"Identifiant invalide.") · 401 · 403 for a non-patient or without the CSRF header · **403 `PATIENT_PROFILE_REQUIRED`**
"Complétez votre profil patient avant de réserver." · 404 `NOT_FOUND` unknown slot · 409 `SLOT_TAKEN` "Ce créneau
n'est plus disponible. Choisissez-en un autre." (already booked, or started).

**Built:**
- `AppointmentController.book` (`@PreAuthorize` PATIENT): `slotId` arrives as text in `BookAppointmentRequest` and
  is read after the role check, so a non-patient gets 403 even with `"abc"` or `{}` (same reason as the MLink-100
  review). The patient is always the caller: `patient_id` = `booked_by` = `currentUser.id()`; any id in the body is
  ignored. `AppointmentMapper` → `AppointmentDto` (the practitioner's names only).
- `AppointmentService.book`, one transaction: the patient profile exists, then `SlotRepository.findForBookingById`
  locks the slot row (`SELECT … FOR UPDATE`), the slot must be FREE and not started, it becomes BOOKED and the
  appointment is saved. Two bookings of one slot run one after the other: the second sees it taken (MLink-103 proves
  the race and translates the unique-index violation, the last guard).
- **Gap found and closed:** `appointments.patient_id` references `patients`, a row only registration makes. A patient
  made elsewhere (the Keycloak console, the realm's `patient.dev`) has none, and booking hit a 500. The service now
  answers 403 `PATIENT_PROFILE_REQUIRED`. For the dev stack, `keycloak/dev/dev-practitioners.json` gets a demo patient,
  **`patient.demo@medlink.local`** (fixed id `d0c70000-0000-4000-8000-000000000010`, group `/patients`, dev password),
  and the dev seed its `users` and `patients` rows. Still dev only (`RealmImportSeparationTest`).
- `AccessMatrixFunctionalTest`: one line, `POST /api/appointments` (anonymous 401, patient open, practitioner and staff
  403). Postman folder 7: ten requests (earliest free slot; `patient.dev` → 403 `PATIENT_PROFILE_REQUIRED`; log in as
  `patient.demo` → 201; the slot leaves the search; the same slot again → 409 `SLOT_TAKEN`).

**Proof** (Ubuntu VM with Docker, then CI):
- Red first (be498f4): `AppointmentBookingFunctionalTest` 10 tests, 8 failures (404, the route did not exist; the
  anonymous 401 and the missing-CSRF 403 already held) and the access matrix.
- Green (4bb50bc): the two classes, 83 tests, 0 failures.
- Mutation check: without `slot.setStatus(SlotStatus.BOOKED)`, two tests turn red (`aPatientBooksAFreeSlotAndItLeavesTheSearch`:
  the slot stays FREE; `aTakenSlotIsRefusedAndTheFirstBookingStays`: the unique index answers 500 instead of 409).
- Demo patient: red first (34a3f36; its CI run was cancelled by the next push, so proved in the VM with the data of
  4bb50bc: `RealmImportSeparationTest` and `DevSeedTest`, 2 failures, exactly the two new checks), green (6155802).
- `./mvnw -B clean verify` **473 tests, 0 failures**; reverse order 473, 0 failures; CI on 6155802 green
  (`build-test`, `dependency-check`).
- `setup-dev --skip-tests` (0 warnings) then newman: **132 requests, 314 assertions, 0 failures**.

## MLink-163 — the slot search form's lists, `GET /api/slots/filters` (2026-11-07, epic/MLink-8)

Task 25.6 of MLink-25, added for the search page (MLink-101): the form offers lists (clinic, specialty,
practitioner) instead of ids to type, and choosing a clinic shows only its practitioners.

**Contract:** session required, PATIENT only. `GET /api/slots/filters` → 200
`{ "clinics": [ { "id", "name", "city" } ], "specialties": [ { "id", "name" } ], "practitioners": [ { "id",
"firstName", "lastName", "specialtyId", "clinicIds": [ ] } ] }`, each list in French order by name (practitioners by
last name, then first name); 401 `UNAUTHENTICATED` without a session; 403 for a practitioner or staff. Of a
practitioner, only what the form needs: never an email or a username.

**Built:**
- `SlotController.filters()` (`@GetMapping("/filters")`, `@PreAuthorize` PATIENT) → `SlotMapper.toFiltersResponse`
  → `SlotFiltersResponse` (records `ClinicItem`, `SpecialtyItem`, `PractitionerItem`).
- `SlotService.filters()` returns `SlotFilters` (entities, no DTO), sorted with a French `Collator` in Java: a few
  dozen rows, and SQL ordering would depend on the database collation ("Pédiatrie" must come before
  "Pneumologie", not after "Z").
- `PractitionerRepository.findAllBy()` with `@EntityGraph({"specialty", "clinics"})`: every practitioner with
  their specialty and clinics in one query, no N+1.
- `AccessMatrixFunctionalTest`: one line, `GET /api/slots/filters` (anonymous 401, patient open, practitioner and
  staff 403). Postman folder 7: one request on the dev seed (the three clinics and specialties, the cardiologist's
  specialty and two clinics, no `@` in the body).

**Proof** (Ubuntu VM with Docker):
- Red first (26a1a96, the tests alone): `SlotFiltersFunctionalTest` and the access matrix, 6 failures (404 instead
  of 200 or 403, and a matrix line with no route).
- Green (64ca169): both classes pass.
- Mutation check: without the `@PreAuthorize` of `filters()`, `aPractitionerAndStaffAreRefused` turns red
  (expected 403 but was 200).
- `./mvnw -B clean verify` **458 tests, 0 failures**; reverse order 458, 0 failures.
- `setup-dev` then newman (b8d75aa): **122 requests, 295 assertions, 0 failures**.

## MLink-99 — slot search index, an answer in less than 2 seconds (2026-11-07, epic/MLink-8)

Task 25.3 of MLink-25, its acceptance criterion: "the results show in less than 2 seconds".

**Built:**
- `V14__slot_search_index.sql`: `CREATE INDEX slots_free_by_start ON slots (starts_at, id) WHERE status = 'FREE'`.
  Partial (free slots only) and in the search's own order (`ORDER BY starts_at, id`): the search reads the first
  rows of the period and stops at the page, instead of reading the whole table and sorting it. No code change.
- No index for the practitioner filter: `slots_practitioner_starts_at_unique (practitioner_id, starts_at)` (V13)
  already serves it. No clinic or specialty index: a test with one clinic could not show it helps, so none without
  proof (the ticket asked for them only "if the EXPLAIN shows it").
- `SlotSearchIndexTest` (real PostgreSQL, Testcontainers): 50,000 slots (50 practitioners × 1,000 back-to-back
  30-minute slots, every fifth booked), `ANALYZE`, then `EXPLAIN` of the search's SQL with no filter (the widest
  search): the plan uses `slots_free_by_start` and holds no `Seq Scan on slots`.
- `SlotSearchPerformanceFunctionalTest` (real API, real port, a patient logged in): the same 50,000 slots, in 2031
  so no other test sees them, then `GET /api/slots` over 31 days with `size=50`: 200 in less than 2 seconds,
  `total` 40,000, 50 items. The rows are deleted afterwards (`@AfterEach`).

**Proof** (Ubuntu VM with Docker):
- Red first (e95aab9, the tests alone): `SlotSearchIndexTest` fails; the timing test is already green (50,000 rows
  are little for PostgreSQL: it guards the criterion, the index test proves the index). The plan without the index:
  ```
  Limit  (cost=3046.61..3046.66 rows=20 width=920)
    ->  Sort  (cost=3046.61..3146.85 rows=40097 width=920)
          Sort Key: s.starts_at, s.id
          ->  Hash Join  (cost=55.97..1979.64 rows=40097 width=920)
                ...
                                  ->  Seq Scan on slots s  (cost=0.00..1493.00 rows=40097 width=64)
                                        Filter: ((starts_at >= '2031-03-03 08:00:00+00') AND (starts_at < '2031-04-03 08:00:00+00') AND ((status)::text = 'FREE'::text))
  ```
  every free slot of the month read, joined, then sorted, to keep 20.
- Green (32fd3f1): both tests pass; the plan reads `slots_free_by_start`.
- Mutation check: without the `CREATE INDEX` line, `SlotSearchIndexTest` turns red again (`Seq Scan on slots`).
- `./mvnw -B clean verify` **450 tests, 0 failures**; reverse order 450, 0 failures.
- `setup-dev --reset` then newman: **121 requests, 290 assertions, 0 failures**.

## MLink-100 — slot search input rules and "no result" message (2026-10-24, epic/MLink-8)

Task 25.4 of MLink-25: wrong input to `GET /api/slots` is refused clearly, and an empty search says so.

**Contract** (added to MLink-98's): every broken rule answers 400 `VALIDATION_FAILED`, one entry per field in
`details`, in French:

| Input | `details` |
|---|---|
| `from` or `to` missing (MLink-98) | `from`: "La date de début est obligatoire." / `to`: "La date de fin est obligatoire." |
| `to` not after `from` | `to`: "La date de fin doit être après la date de début." |
| more than 31 days between `from` and `to` (31 days exactly is accepted) | `to`: "La période ne peut pas dépasser 31 jours." |
| `page` below 0 | `page`: "Le numéro de page ne peut pas être négatif." |
| `size` outside 1–50 | `size`: "La taille de page doit être comprise entre 1 et 50." |
| an id that is not a UUID | the field (`clinicId`, `practitionerId`, `specialtyId`): "Identifiant invalide." |
| a date that cannot be read | the field: "Date invalide (format attendu : 2026-10-27T09:00:00Z)." |

Nothing matches at all: 200 `{ "items": [], "total": 0, "message": "Aucun créneau disponible. Élargissez la
recherche (dates, clinique ou spécialité)." }`; `message` is absent whenever something matches (and on a page past
the last one, where `total` is not 0).

**Code owner's review on PR #87 (2026-11-02): the role before the parameters.** Spring converted the typed
parameters (`Instant`, `UUID`, `int`) before `@PreAuthorize` ran, so a practitioner sending `clinicId=abc` got this
controller's 400 instead of the 403 of a patient-only endpoint (the "Limits" below). `SlotController.search` now
takes every parameter as text and reads it itself, after the role check (`instant`, `uuid`, `integer`), with the same
French messages; the local `MethodArgumentTypeMismatchException` handler is gone, and an unreadable value is now
listed together with the other broken fields. A patient sees no change. New functional test
`aPractitionerAndStaffAreRefusedEvenWithUnreadableParameters`: a practitioner and a staff member send `clinicId=abc`,
`practitionerId=abc`, `from=demain`, `page=x`, `size=51` and no parameter at all, and get 403 every time. The same day
`master` was merged (75 commits, conflict in this report only, both sides kept): Scalar replaces Swagger UI, which
clears the SCA finding (DOMPurify) that blocked this PR. Proof:
- red first (a31ac35): `SlotSearchFunctionalTest` 12 tests, 1 failure, `practitioner …&clinicId=abc`
  expected 403 but was 400;
- green (1eef08b): `./mvnw -B clean verify` **448 tests, 0 failures**; reverse order 448, 0 failures;
- `setup-dev --reset` then newman: **121 requests, 290 assertions, 0 failures**;
- CI on 1eef08b: every check green, `SCA-Dependency-Check` included; PR mergeable.

**Built:**
- `SlotController`: the rules, all collected before answering, so one call lists every broken field. The
  31-day bound and the 50-slot page bound the work one request can ask of the database (`MAX_PERIOD`,
  `MAX_PAGE_SIZE`). An unreadable parameter (`MethodArgumentTypeMismatchException`) gets our error body through an
  `@ExceptionHandler` local to this controller: the shared `GlobalExceptionHandler` is unchanged.
- `SlotSearchResponse` gains `message` (`@JsonInclude(NON_NULL)`); `SlotMapper` sets it when `total` is 0.
- `SlotSearchFunctionalTest`: 5 new tests through the real API (end before start; 31 days accepted, 31 days and
  one second refused; unreadable ids and date named; `size` 0 and 51 and `page` -1 refused, 1 and 50 accepted;
  the message when nothing matches, absent when something does).
- Postman folder 7: five more requests (end before start, 32 days, `clinicId=abc`, `size=51`, a week next year
  with nothing seeded: the message).

**Proof** (Ubuntu VM with Docker, then CI):
- Red first (59cdab5, the tests alone): `SlotSearchFunctionalTest` 11 tests, 5 failures, exactly the 5 new ones;
  MLink-98's six stay green.
- Green (3b601aa): `./mvnw -B clean verify` **284 tests, 0 failures**; reverse order: 284, 0 failures. CI on PR #87:
  9 checks green.
- Mutation check: with a 10-year bound instead of 31 days, `thePeriodIsThirtyOneDaysAtMost` turns red.
- `scripts/setup-dev.sh --skip-tests`: 0 warnings. Postman, `npx newman@6.2.1 run`: **80 requests, 211 assertions,
  0 failures** (folder 7 with the five new cases).

**Decisions** (also in the log below): the messages above; 31 days at most, exactly 31 accepted; pages of 1 to
50, numbered from 0; the type-mismatch handler local to `SlotController`; the message only when nothing matches.

**Limits:** ~~an unreadable parameter is converted by Spring before `@PreAuthorize`: a practitioner sending
`clinicId=abc` gets this 400, not 403~~. Fixed on 2026-11-07 after the code owner's review (see above).

## MLink-98 — slot search, `GET /api/slots` (2026-10-23, epic/MLink-8)

Endpoint 25.2 of epic MLink-8, the first endpoint of the epic: a patient searches the free slots.

**Contract** (patients only): `GET /api/slots?from=&to=&clinicId=&practitionerId=&specialtyId=&page=0&size=20`,
`from` and `to` required (ISO-8601), the three filters optional and combined. 200
`{ "items": [ { "slotId", "startsAt", "endsAt", "clinic": { "id", "name" }, "practitioner": { "id", "firstName",
"lastName" }, "specialty" } ], "total" }`: only `FREE` slots starting in `[from, to)`, never in the past (a past
`from` starts at now), earliest first; `total` counts every match. 400 `VALIDATION_FAILED` when `from` or `to` is
missing (one entry per field in `details`); 401 anonymous; 403 practitioner and staff.

**Built:**
- `dto/SlotDto` (nested `ClinicRef`, `PractitionerRef`), `dto/SlotSearchResponse`, `mapper/SlotMapper`.
- `service/SlotService.searchFree(...)`: one JPA Specification (status, period, optional filters), sorted by
  `startsAt`, paged; returns entities. `SlotRepository` gains `JpaSpecificationExecutor` and an
  `@EntityGraph` on `findAll(spec, pageable)`, so the practitioner, specialty and clinic come in the same query.
- `controller/SlotController`: `@PreAuthorize` `PATIENT` (constants of `security/Roles`). `from` and `to` are
  declared optional and checked in the method: Spring checks a required parameter *before* `@PreAuthorize`, so a
  practitioner without dates would get 400 instead of 403.
- `AccessMatrixFunctionalTest` (MLink-60, said on MLink-60): `GET /api/slots` → anonymous 401, patient past
  security, practitioner 403, staff 403.
- Postman: folder 7 (folder 6 until #89) logs in as `patient.dev` and searches the dev seed (401 without a session, 200 with the
  expected shape, earliest first, no email, filter by practitioner, 400 without `from`).

**Proof** (Ubuntu VM with Docker):
- `functional/SlotSearchFunctionalTest` (real API, Keycloak, database; each test writes its own practitioners,
  clinics, specialties and slots): only the free slots of the period come back, earliest first, never a booked,
  later or past one (a past `from` starts at now), with the expected JSON and no email; each filter narrows and
  they combine; paging (`size=2`: 2 + 1, `total` 3); 400 without `from`; anonymous 401; `practitioner.dev` and
  `staff.dev` 403.
- Red first, with the tests and the matrix line only: `SlotSearchFunctionalTest` 3 failures and 2 errors (no
  route: 404), `AccessMatrixFunctionalTest` red on the route list and on the practitioner and staff cells (404,
  not 403); the anonymous cases already answer 401 (every `/api` path needs a session).
- Mutation check: without the `FREE` rule in `SlotService`,
  `aPatientGetsOnlyTheFreeSlotsOfThePeriodThatStartInTheFutureEarliestFirst` turns red (the booked slot comes back).
- `./mvnw -B clean verify`: **242 tests, 0 failures** (with the overlap fix below); reverse order: 242, 0 failures.
- `scripts/setup-dev.sh --reset --skip-tests`: 0 warnings. Postman, `npx newman@6.2.1 run`: **57 requests,
  163 assertions, 0 failures**, the slot search folder included (before the merge of #89).

**Decisions** (also in the log below): patients only; a past `from` starts at now; `page=0`, `size=20` by
default; earliest first; of the practitioner only the id and names.

**Limits:** the other input rules (from before to, at most 31 days, page size 1–50, invalid UUID as our error body)
and the "no result" message are MLink-100 (25.4); today an invalid UUID or date gets Spring's 400. The 2-second
target on a large data set is MLink-99 (25.3).

## MLink-97 — appointment domain tables and dev seed (2026-10-21, epic/MLink-8)

First task of epic MLink-8 (MLink-25 search, 27 booking, 28 cancel/reschedule, 36 reminders). Everything the epic's
endpoints need in the database, and enough dev data to try them. No endpoint. Shared files touched by the review
fixes: `compose.yaml` (one dev-only service) and `ComposeFilesTest` (a `DEV_ONLY` list), announced to Mohamed.

**Built:**
- `V13__appointment_domain.sql` (V9, then V10 after #89, V12 after #97 took V11, V13 since #99 took V12 for `email_token_mfa_enrolment`): `clinics`, `specialties`, `practitioners` (id = `users.id`, the Keycloak id; names
  and specialty), `practitioner_clinics`, `slots` and `appointments`. Rules held by the database: one practitioner
  never has two slots starting at the same time (`UNIQUE (practitioner_id, starts_at)`), a slot ends after it
  starts, slot status is `FREE`/`BOOKED`, appointment status `BOOKED`/`CANCELLED`, an appointment belongs to a
  patient (`patient_id` → `patients`, the owner checked on cancel/reschedule) and remembers who booked it
  (`booked_by` → `users`). Times are `timestamptz` (UTC).
- `model/`: `Clinic`, `Specialty`, `Practitioner extends User` (JOINED, like `Patient`), `Slot`, `Appointment`
  and their status enums; one plain `JpaRepository` each.
- Dev seed `db/dev-seed/R__dev_appointment_seed.sql`, loaded by the `dev` profile only (`application-dev.yml`,
  `spring.flyway.locations`): Sousse, Sfax and Tunis clinics; Médecine générale, Cardiologie, Pédiatrie; three
  practitioners in two clinics each; free 30-minute slots Monday to Friday, 08:00–12:00 and 13:00–17:00 Tunis
  time, for the next 14 days. Every insert skips what exists, so the seed can run again.
- Dev practitioners: three Keycloak users with **fixed ids** (`generalist.dev`, `cardiologist.dev`,
  `pediatrician.dev`, ids `d0c70000-0000-4000-8000-00000000000{1,2,3}`), so they log in onto their seeded rows.
  They are **not** in the shared realm file: they live in `keycloak/dev/dev-practitioners.json` and are added by
  the dev-only compose service `keycloak-dev-users` (see the review below). `practitioner.dev` is unchanged.

**Code owner's third review on PR #87 (2026-10-29): a slot is in one of its practitioner's clinics.**
Nothing stopped a slot in a clinic where its practitioner does not work. The slots table now carries
`CONSTRAINT slots_practitioner_works_in_clinic FOREIGN KEY (practitioner_id, clinic_id) REFERENCES
practitioner_clinics (practitioner_id, clinic_id)`; a clinic that still holds a practitioner's slots cannot be taken
off them. The dev seed already put each slot in one of its practitioner's two clinics: no seed change. The slot
helpers of `AppointmentSchemaTest` and `SlotSearchFunctionalTest` link the practitioner to the clinic first, and
`AppointmentDomainRepositoryTest` flushes the practitioner before saving a slot (Hibernate writes the
`practitioner_clinics` rows after the entity inserts). New tests: a slot in a clinic where the practitioner does not
work is refused, a slot in one of their clinics is accepted. Same day, `master` merged twice: #97 took V11 and #99
took V12, so the migration is now **V13** (17986d0); a dev database that ran an older number needs `--reset` once.
The first merge dropped the `devPassword` Postman variable (folder 7's login answered 401); restored in 1e2c6ec.
Proof:
- red first (172e4a0, CI red on that commit): `AppointmentSchemaTest` 16 tests, 1 failure,
  `aSlotInAClinicWhereThePractitionerDoesNotWorkIsRefused`; green (4107099): the four touched classes, 35 tests,
  0 failures;
- mutation check: without the constraint, the same test turns red again (16 tests, 1 failure);
- `./mvnw -B clean verify` 411 tests, 0 failures (4107099); after the V13 merge, reverse order
  (`-Dsurefire.runOrder=reversealphabetical`) 434 tests, 0 failures, and CI `build-test` green (7cec64c);
- `setup-dev --reset` then newman: 119 requests, 285 assertions, 0 failures (V13).

**Code owner's second review on draft PR #87 (2026-10-23): slots of a practitioner must not overlap.**
`UNIQUE (practitioner_id, starts_at)` only refused equal start times: 09:00–10:00 and 09:30–10:00 could both exist.
V9 (still not merged, changed in place) now creates the `btree_gist` extension and adds
`CONSTRAINT slots_no_overlap EXCLUDE USING gist (practitioner_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&)`:
two slots of one practitioner never overlap, in any clinic; `[)` makes 09:00–09:30 and 09:30–10:00 adjacent, so
both are accepted. The unique start time stays (agenda index, the dev seed's `ON CONFLICT` target). New tests in
`AppointmentSchemaTest`: an overlapping slot is refused even in another clinic, adjacent slots are accepted, two
practitioners may have slots at the same time. Proof: red first (14 tests, 1 failure:
`overlappingSlotsOfOnePractitionerAreRefusedEvenInAnotherClinic`), then green; `./mvnw -B clean verify` 242 tests,
0 failures, both orders; mutation check: without `slots_no_overlap` the overlap test turns red again.

**Code owner's review on draft PR #87 (2026-10-22):**
- *One active appointment per slot, enforced by the database.* V9 (not merged yet, so changed in place) gains
  `CREATE UNIQUE INDEX appointments_one_active_per_slot ON appointments (slot_id) WHERE status = 'BOOKED'`: two
  racing bookings cannot both hold a slot, and a cancelled appointment does not block a new booking. MLink-103
  (27.2) keeps the API side: translate the violation into 409 `SLOT_TAKEN` and prove the race through the API.
- *No dev users in what deployed environments import.* `keycloak/realm-medlink.json` (copied to the servers by
  `.github/actions/deploy-compose`, mounted by `docker-compose.yml`) is back to `master`'s content. The three
  practitioners moved to `keycloak/dev/dev-practitioners.json`; a one-shot service in `compose.yaml` only,
  `keycloak-dev-users` (Keycloak image, `keycloak/dev/import-dev-users.sh`), waits for Keycloak, logs in as the dev
  admin and runs Keycloak's partial import (`kcadm.sh create partialImport`, existing users skipped), which keeps
  the fixed ids. The partial import does not resolve `${...}`, so the script fills `${KC_DEV_USERS_PASSWORD}` from
  its environment; the file holds no password. The setup scripts are unchanged: they start every service of
  `compose.yaml`. `ComposeFilesTest` (MLink-113) gains a `DEV_ONLY` list, left out of the dev/full comparison.
- New `RealmImportSeparationTest` (reads the files, no Docker): the dev file holds the three practitioners with
  fixed ids and a password placeholder; the shared realm holds none of their usernames or ids;
  `docker-compose.yml` and the deploy action never mention `keycloak/dev` or the service; in `compose.yaml` only
  `keycloak-dev-users` mounts `keycloak/dev`. Red first (dev file and service missing: 3 of 4 red, the "nothing
  used to deploy mentions the dev folder" guard green), then green with `ComposeFilesTest` (9 tests, 0 failures).
  Mutation check: a dev practitioner put back into the shared realm turns
  `theSharedRealmHoldsNoneOfTheDevPractitioners` red.
- Proof of the review fixes (Ubuntu VM with Docker): red first with the new tests only, `AppointmentSchemaTest`,
  `DevSeedTest` and `RealmImportSeparationTest` give 21 tests, 2 failures and 3 errors, all expected
  (`aSlotHoldsOneActiveAppointmentOnly`: a second active appointment is still accepted; the dev users file and the
  dev-only service do not exist yet). With the fixes, `./mvnw -B clean verify`: **228 tests, 0 failures**; reverse
  order: 228, 0 failures. Mutation check: without `appointments_one_active_per_slot`,
  `aSlotHoldsOneActiveAppointmentOnly` turns red. `scripts/setup-dev.sh --reset --skip-tests`: 0 warnings;
  `keycloak-dev-users` logs show the partial import `ADDED` each user **with its fixed id**; the three practitioners
  log in (200 ×3) onto their seeded rows (160 free slots each).

**Proof** (run in an Ubuntu 22.04 VM with Docker; the Windows machine has no Docker):
- Red first (6f0676d): `AppointmentSchemaTest` (9) and `DevSeedTest` (6) fail on missing tables and on the realm
  users not existing yet; only the "practitioner.dev unchanged" guard passes.
- Green: those 15 plus `AppointmentDomainRepositoryTest` (2: the entities read and write the tables, a saved
  practitioner writes `users` and `practitioners` under one id). `DevSeedTest` reads the dev users file (the realm
  file before the review): the seeded practitioners have its ids, emails and the expected specialties, slots are weekday office hours in Tunis
  time, and running the seed again adds nothing; without the `dev` profile there is no seed data.
- Mutation check: without `UNIQUE (practitioner_id, starts_at)`,
  `onePractitionerCannotHaveTwoSlotsStartingAtTheSameTime` turns red.
- `./mvnw -B clean verify`: **222 tests, 0 failures**; `-Dsurefire.runOrder=reversealphabetical`: 222, 0 failures.
- `scripts/setup-dev.sh --reset --skip-tests`, then `POST /api/auth/login` as each new practitioner: 200 ×3; the
  dev database holds the three practitioners with 160 free slots each (10 weekdays × 16 slots).

**Decisions** (also in the log below): migration V9, not V8 (the code owner's call on PR #60: the PR merged
first holds the lower number); practitioners' names stored in `practitioners`, like patients'; the seed is a
repeatable SQL migration in its own dev-only location; fixed ids for new dev users only.

**Limits:**
- Seeded slots are computed when the seed runs: after two weeks they are in the past. Run the setup script with
  `--reset` (`-Reset`) before a demo.
- The older dev users `staff.dev`, `practitioner.dev` and `patient.dev` are still in the shared realm file, so
  they still reach deployed environments. Moving them touches `FunctionalTestBase` and the team's tests: raised on
  PR #87, a separate ticket proposed.
- In full-stack mode (`docker-compose.yml`, setup `--full-stack`) the three dev practitioners are not created, on
  purpose; the dev seed still inserts their rows, they just cannot log in there.
- V13 runs `CREATE EXTENSION IF NOT EXISTS btree_gist`: a trusted extension, so the database owner can create it;
  a deployed database whose migration role is not the owner needs it created once by an administrator.
- `docker compose up -d` does not fail when `keycloak-dev-users` fails (it exits after its job): check
  `docker compose logs keycloak-dev-users` if a dev practitioner cannot log in.
- `appointments` has no `ON DELETE CASCADE` (an appointment is a medical record): deleting a patient who has
  appointments (`DELETE /api/users/{id}`) would be refused by the database and answer 500 `INTERNAL_ERROR` (read
  from the code, not tested: no endpoint creates appointments yet). To settle with account management (MLink-20,
  sub-sprint 5).
- The pre-commit hooks could not run: the gitleaks hook calls `protect --source=.`, which gitleaks v8.24.0 no
  longer accepts, and checkov 3.2.5 does not install on Windows with Python 3.14 (it needs a Rust compiler for
  `rustworkx` 0.13). Commits were made with `SKIP=gitleaks,checkov`; CI's secrets scan covers the PR. Reported to
  the code owner (`.pre-commit-config.yaml`).

## GitHub #48 — AI disclosure is conditional and truthful (2026-10-21, fix/48-ai-disclosure)

**PR:** [#82](https://github.com/assali-mohamed-ali/api-MedLink/pull/82)

**Built:** the `Utilisation de l'IA` section now offers two exclusive usage choices ("no AI" or "AI used")
and the human review acknowledgment. `ai-disclosure.yml` runs a small Node validator
(`.github/scripts/validate-ai-disclosure.mjs`) that fails when neither or both usage choices are checked,
when AI use has no review acknowledgment, when "no AI" is combined with the review acknowledgment, and
when a checklist line is missing or duplicated. Failure messages say exactly what to fix. The validator's
own tests run in the workflow before it validates the PR.

**Proof:** `node --test .github/scripts/validate-ai-disclosure.test.mjs`: 10 tests, 0 failures, covering
no-AI, AI + review, AI without review, both choices, no choice, missing/duplicated lines, CRLF, indentation
and uppercase `X`.

**Limits:** the workflow can only check the PR body; it cannot know whether an author lied. The template and
`CLAUDE.md` now state the intended declaration in the same words as the validator.

## MLink-60 — role × route matrix (2026-10-20, epic/MLink-6)

**Built:** one test file, `functional/AccessMatrixFunctionalTest`, no production code and no shared file changed.
At the top of the class, one line per `/api` route and the expected answer for each caller, against the real
API, Keycloak and database. `OPEN` = past security and the endpoint did not break (2xx to 4xx but 401/403: 200,
204, 400, 404…; never a 5xx), `LOGIN` = 401, `FORBIDDEN` = 403. No cell may answer through a redirect.

| Route | Anonymous | Patient | Practitioner | Staff |
|---|---|---|---|---|
| `GET /api/auth/csrf` | OPEN | OPEN | OPEN | OPEN |
| `POST /api/auth/register` | OPEN | OPEN | OPEN | OPEN |
| `POST /api/auth/verify-email` | OPEN | OPEN | OPEN | OPEN |
| `POST /api/auth/verify-email/resend` | OPEN | OPEN | OPEN | OPEN |
| `POST /api/auth/login` | OPEN | OPEN | OPEN | OPEN |
| `POST /api/auth/logout` | LOGIN | OPEN | OPEN | OPEN |
| `GET /api/users` | LOGIN | FORBIDDEN | FORBIDDEN | OPEN |
| `GET /api/users/{id}` | LOGIN | FORBIDDEN | FORBIDDEN | OPEN |
| `DELETE /api/users/{id}` | LOGIN | FORBIDDEN | FORBIDDEN | OPEN |
| `GET /api/users/_health` | LOGIN | OPEN | OPEN | OPEN |

- Each cell is its own test (40), with a fresh browser logged in as the realm's `patient.dev`,
  `practitioner.dev` or `staff.dev`, so a logout cannot spoil another cell and each has its own IP (rate limits).
- Every call carries the CSRF header: a 403 is a refusal by role or ownership, never a missing token. `{id}` is a
  random UUID (someone else's: a patient gets 403, staff 404). Writes send `{}`, so public endpoints answer 400
  and nothing is created.
- `everyApiRouteHasALineInTheMatrix` reads every `/api` route from Spring's `RequestMappingHandlerMapping` and
  fails when the routes and the matrix differ, either way (a mapping with no HTTP method shows as `ANY`).

**Adding an endpoint (Ela, Mohamed, Chourouk, sub-sprint 5):** add one `new Rule(...)` line to `MATRIX` with
the four expected answers, in the same PR as the endpoint. A path variable stays as written in the mapping
(`{id}`); a path variable other than `{id}` needs a value in `send(...)`.

**Proof:** red first (eb6472d: empty matrix, Spring listed exactly the 10 routes), then green (19b0157): **41 tests,
0 failures**. Mutation checks, each restored after: a new `GET /api/users/_probe` without a line fails
`everyApiRouteHasALineInTheMatrix`, which names `"GET /api/users/_probe"` (the build fails on a forgotten route);
removing `@PreAuthorize` from the user list fails the patient and practitioner cells of `GET /api/users`;
making `/api/users/_health` public fails its anonymous cell. Each break failed only its own cells.
`./mvnw -B verify`: green; reverse class order: **201 tests, 0 failures**. No endpoint change, so no Postman change.
After the code owner's review on #77 (non-blocking: `OPEN` also let a 5xx or a redirect through): `OPEN` now
requires 2xx–4xx, and every cell checks that no redirect was followed. Still 41 green. Mutation checks: a
`GET /api/auth/csrf` that throws (500) fails its four `OPEN` cells; a `/api/users/_health` that redirects fails its
three logged-in cells on "no redirect".

**Limits:** the matrix checks who gets past security, not what the answer holds (that is each feature's own
tests, e.g. `OwnershipFunctionalTest`). Routes outside `/api` (actuator, Swagger) are not in it: their exposure
in production is GitHub #49 and #65. Maven's console names the cells by number (`[26]`); IDEs and the
surefire XML report show the readable name.

## GitHub #54 — the SCA fallback is visible and gated (2026-10-21, fix/54-sca-fallback)

**PR:** [#83](https://github.com/assali-mohamed-ali/api-MedLink/pull/83)

**Built:** `scripts/ci/sca-policy.sh` decides the scanner from the event, ref, `NVD_API_KEY` and the new
repository variable `ALLOW_TRIVY_FALLBACK`. The workflow now writes the scanner, the reason and the policy
to the log, the step summary and `target/sca-scanner.txt` (uploaded in `sca-reports`), and fails when the
selected scanner produced no report. Release branches (`master`, `staging`, `prod`, `v*` tags) fail on the
Trivy fallback unless `ALLOW_TRIVY_FALLBACK=true` explicitly accepts it; PRs, schedules, manual runs and
feature-branch pushes may fall back with a visible warning. `SECURITY.md` documents what Trivy covers and
what it does not cover (NVD/CPE/transitive Java analysis, suppressions, unfixed CVEs).

**Proof:** `scripts/ci/sca-policy.test.sh` covers the NVD-key path (always primary), the missing-key paths
for PRs, schedules, manual runs and feature branches (fallback allowed), release branches and `v*` tags
(denied), the explicit `ALLOW_TRIVY_FALLBACK` acceptance and an unknown event (denied). It runs in the SCA
workflow before the scan.

**Limits:** the policy cannot make Trivy equivalent to OWASP Dependency-Check; the documented answer is to
set `NVD_API_KEY` for release scans. The fallback still runs (and publishes findings) before the release
policy fails, so a red run has usable evidence.

## MLink-121 — staff without Keycloak admin rights, GitHub #80 (2026-10-20, fix/80-staff-keycloak-rights)

**Problem** (found by the threat model update, row E3, #79): the realm role `ADMINISTRATIVE_STAFF` was a composite
of `realm-management` `view-users`, `manage-users`, `query-users`, `query-groups` and `view-realm`. With only their
own password and Keycloak's public client `admin-cli` (present in every realm, no secret), a staff member could
list every user, create an account and read the realm settings straight from Keycloak's admin API, outside the
API's rules, ownership checks and audit trail.

**Fix:** `keycloak/realm-medlink.json`, the role `ADMINISTRATIVE_STAFF` is a plain role, like `PRACTITIONER` and
`PATIENT`. Nothing in the API used those rights: every admin call goes through the service account
`medlink-admin`, which keeps its own `realm-management` roles. Staff still manage accounts, through the API.

**Proof:** `StaffKeycloakRightsFunctionalTest` (real Keycloak), committed red first (2e207fe): with a token staff.dev
gets from `admin-cli`, listing users answered 200, creating a user 201, reading the realm 200; the service
account's call 200. After the fix: 403, 403, 403, and still 200 for `medlink-admin`. Each test first checks that
Keycloak issued the token, so a 403 means no rights, never "not logged in". The red commit is the mutation check
(the old composite turns the three staff tests red). `./mvnw -B verify`: green; reverse class order: **164 tests, 0
failures**; sign-up (`SignupFunctionalTest`) still creates users through `medlink-admin`.

**Existing environments:** Keycloak imports the realm file only into an empty database. In staging and prod an
admin must make the same change by hand: Keycloak console → realm `medlink` → Realm roles → `ADMINISTRATIVE_STAFF`
→ Associated roles → remove the five `realm-management` roles. Locally the setup script sees the changed realm
file and re-imports it (dev volumes wiped, the realm is saved first to `.dev/realm-backups/`); not run here, the
dev stack does not start on `master` since #74 (postgres 18).

**Limits:** `admin-cli` still lets any user get a Keycloak token with their password, which skips the API's per-IP
limit (Keycloak's lockout still applies). That token now grants no admin right; closing Keycloak's port to the
public in production (threat model E4) removes the path. The threat model's E3 row turns ✅ once this and #79 are
both on `master`.

## GitHub #53 — Terraform matches the Keycloak BFF (2026-10-21, fix/53-terraform-env)

**PR:** [#84](https://github.com/assali-mohamed-ali/api-MedLink/pull/84)

**Built:** `terraform/main.tf` now injects every setting the production profile needs: database,
both Keycloak clients (public/internal URLs, realm, client id and the BFF/admin secrets), SMTP
(host, port, user, password, sender), frontend URL and CORS. The obsolete `JWT_SECRET` variable is
removed from `variables.tf` and all three tfvars examples. The app image examples use immutable
digests. Secret variables are `sensitive`; `terraform/README.md` documents the required values, the
Postgres/Keycloak networking contract, remote state (`backend.tf.example`) and rollback.
`scripts/ci/check-prod-config.sh` checks the container environment against the production profile
(and rejects any `JWT_SECRET` reference); CI also runs `terraform init -backend=false`,
`terraform fmt -check` and `terraform validate`.

**Proof:** `scripts/ci/check-prod-config.sh` passes; `terraform fmt -check` and `terraform validate`
pass with Terraform 1.9.8. `python3 -c 'import yaml; yaml.safe_load(...)'` on `ci.yml`: valid.

**Limits:** Terraform still deploys only the API container; PostgreSQL and Keycloak are external
dependencies (documented). Provisioning Keycloak/keycloak-db belongs to the deployment work in #47.

## MLink-84 — session cookie hardening (2026-10-19, feat/ss2-session-cookie-hardening)

Unblocked by decision #58 option 1 (keep the password grant): no Keycloak redirect comes back, so Strict is safe.
- Session cookie: `same-site=strict` (was `lax`) in `application.properties`, `http-only` as before; `secure: true`
  in `application-staging.yml` and `application-prod.yml`, so even behind a proxy that ends TLS. Dev: not forced.
- `XSRF-TOKEN` cookie (`SecurityConfig`, cookie lines only): `SameSite=Strict`, and `Secure` by the same switch
  (`server.servlet.session.cookie.secure`); still readable by React. Without the switch it keeps the
  repository's default (Secure on an HTTPS request).
- Session id at login: already changed by `LoginSessions.open` (MLink-55, Youssef's code, not changed).
- Not changed: `LoginSessions.close()` expires the cookie with `HttpOnly` only; that still deletes it (said on MLink-54).
- Local full stack: `.env` (from `.env.example`) sets `SPRING_PROFILES_ACTIVE=dev`, so no Secure on plain HTTP;
  Secure only where the staging or prod profile runs (behind HTTPS).

**Proof:** `SessionCookieFunctionalTest` (login's session cookie HttpOnly + Strict; XSRF-TOKEN Strict and readable;
an anonymous session from before the login is not the one the login uses, its id opens nothing) and
`SecureCookiesIntegrationTest` (real staging and prod profile files: both cookies Secure + Strict), red first
(a5504ac), then green; `SecurityCookiesIntegrationTest` Lax → Strict, and not Secure without a profile. Mutation:
the XSRF Secure switch off turns both profile tests red. The fixation test was green from the start (it guards
existing code). Suite: **150 tests, 0 failures**, both class orders; after merging #60: **160, 0 failures**, both orders. No endpoint change, so no Postman change.
`scripts/setup-dev.ps1` 0 failures in both modes (dev 28 OK, `-FullStack` 29 OK): the end-to-end login and sign-up
still work with the Strict cookies.

## GitHub #47 — real CD rollout (2026-10-21, feat/47-cd-compose-rollout)

**PR:** [#85](https://github.com/assali-mohamed-ali/api-MedLink/pull/85)

**Built:** `cd.yml` no longer stops at `terraform validate`. The build job publishes the image and
exports its immutable digest (`repository@sha256:...`). The staging/production jobs run in their
GitHub Environments and call `.github/actions/deploy-compose`: pin the host key, copy
`docker-compose.yml`, the Keycloak realm and monitoring config to `DEPLOY_PATH`, write `.env` with
mode `600` (environment file + `APP_IMAGE`), log in to GHCR on the target, `compose pull`/`up` the API
by digest, verify the running container uses that exact digest, poll `/actuator/health` for
`"status":"UP"`, and print the last 200 API log lines on failure. `docker-compose.yml` now accepts
`APP_IMAGE` (default `api-medlink:local`). `docs/DEPLOYMENT.md` documents the host prerequisites,
environment variables/secrets, immutable-digest rollout, rollback and failure recovery. Production
still requires the `production` environment reviewers and is unreachable from `master`.

**Proof:** `python3 -c 'import yaml; yaml.safe_load(...)'` on `cd.yml` and the composite action: valid;
`bash -n` on every workflow/composite `run` block: clean; `docker compose config --images` with
`APP_IMAGE` set: resolves the app to the supplied digest; the deployment input gate exits 1 with
`DEPLOY_HOST` empty and 0 with every required input set. `ComposeFilesTest` still passes (5 tests).

**Limits:** the real SSH rollout needs the staging/production hosts and environment secrets; it
cannot run in this workspace. The first deployment and the environment review remain human steps.

## MLink-112 — shared rate limits, GitHub #51 (2026-10-18, fix/51-shared-rate-limits)

A condition of keeping the password grant (decision record on #58, option 1, MLink-96;
`docs/decisions/0001-login-password-grant.md` once #58 merges).

**Built:** migration **V8** `rate_limits` (key, hits, expires_at): fixed-window counters in Postgres, so every API
instance shares them. `repository/RateLimitStore.hit` is one `INSERT … ON CONFLICT (key) DO UPDATE … RETURNING`:
the row lock orders hits that arrive together, so no two read the same count (no check-then-act); the
database's clock decides the windows; the key is `<kind>:` + SHA-256 of the subject (no IP or email stored in
clear); ended windows are deleted every 10 minutes (`@Scheduled`, harmless on several instances).
- `security/RateLimitFilter`: per-IP limits on `POST /api/auth/login` (30 per 5 min), `/register` (20 per hour),
  `/verify-email/resend` (20 per hour), `app.rate-limit.*`. Past the limit: **429 `TOO_MANY_REQUESTS`** with
  `Retry-After`, before validation, Keycloak or mail. Keyed by the IP only: the same answer whatever the email,
  so it reveals no account. It runs after Spring Security: a write without the CSRF header is not counted.
- Per account, silent as before (same 201 / 202): `MailThrottle` (one taken-email warning per address per
  15 min) now counts in Postgres instead of a map in memory; resend gained the same atomic count, so a burst
  of resends sends one new link, not one per request. Login per account stays Keycloak's lockout (5 failures,
  15 min, MLink-66): one more counter there would let anyone lock an account faster.
- The client's IP behind a proxy: `server.forward-headers-strategy=native`; Tomcat believes `X-Forwarded-For`
  only from `server.tomcat.remoteip.internal-proxies` = `TRUSTED_PROXIES`, loopback by default. The audit trail
  (MLink-59) now records that IP too, instead of the proxy's.
- `spring.jpa.open-in-view=false` in `application.properties`, as every profile already had: without a
  profile (the tests), a request kept its JPA connection and waited for a second one for the count, and a
  burst emptied the pool.
- Dev profile: the three limits at 1000 (every call comes from localhost; one Newman run would trip them).
- API contract (review on #60): `/v3/api-docs` documents the 429 (the error body, an example, `Retry-After`)
  on exactly the paths `RateLimitFilter.limitedPaths()` returns, through a customizer in `OpenApiConfig`
  next to the CSRF header one: the filter answers before any controller, so springdoc cannot see it.
  `SwaggerRateLimitFunctionalTest` red first, then green; mutation: documenting 429 on every POST turns it red.
  Suite after it: **150 tests, 0 failures**, both class orders. No behaviour change, so no Postman change.
- `FunctionalTestBase.Browser` (shared, said on MLink-97): each browser sends its own `X-Forwarded-For`
  address (198.18.0.0/15), as a visitor behind the proxy; existing tests are unchanged.

**Proof:** `RateLimitFunctionalTest` red first (be41b9c), then green: parallel bursts of login, register and
resend from one IP stop exactly at the limit; another IP keeps its own count; the 429 is the same for a real
account with its right password and for an unknown email, and opens no session; a burst of sign-ups with a
taken email warns the owner once; a burst of resends sends one new link; a forged left entry in
`X-Forwarded-For` does not escape the limit. `RateLimitStoreTest`: 40 hits at once from two connection pools (two
instances) get the counts 1 to 40, each once; windows restart; only hashes are stored. Mutation check: a
read-then-write store turns 5 of these tests red. `./mvnw -B clean verify`: **148 tests, 0 failures**; reverse
class order: **148, 0 failures**. Setup script: 0 warnings. Newman: 147 assertions, 0 failures.

**Limits:** a fixed window lets up to twice the limit through around a window's edge (simple, and enough
against floods). Staging and prod must set `TRUSTED_PROXIES` to their reverse proxy's address, or every client
behind it shares the proxy's count (no proxy is described yet: issue #31, Terraform #53). The proxy must append
the client's address (`$proxy_add_x_forwarded_for`), never pass the client's header as is. People behind one
NAT (a clinic) share one count: the values leave room for that. A taken email still answers a little faster
than a new one (no Keycloak call); the per-IP limit now caps how many emails one IP can probe.

## Docker — backend in the dev compose stack, PR #86 (2026-10-21, feat/dockerize-backend)

**Built:** `compose.yaml` now defines the API under the `full` profile (`docker compose --profile full
up --build`), with fixed dev values for the database, Keycloak clients, Mailpit and the frontend URL;
the default profile is unchanged (API on the host, `spring-boot:run`). `docker-compose.yml` keeps the
same image and gains the same `PGDATA=/var/lib/postgresql/data` pin as `compose.yaml` (the Postgres 18
image otherwise looks in `/var/lib/postgresql/<major>/docker`); its no-env profile default is now `dev`
(staging/prod env files still set `staging`/`prod`). The API datasource in the dev profile
uses the fixed `postgres` service values, so a local `.env` cannot accidentally send it another
password. The ineffective `docker/.dockerignore` moved to the repository root (the build context is
`.`); it now excludes `.env*`, `.git`, `target`, docs, infra and monitoring, so a local secret never
reaches the Docker daemon and the context is smaller. README and architecture docs document the new
profile.

**Proof:** `ComposeFilesTest` passes (5 tests). `docker compose -f compose.yaml config --services`
excludes `app` by default and `--profile full` includes it; `config --images` resolves the app to
`api-medlink:local`. `docker compose --profile full -f compose.yaml build app` succeeds. In a clean
isolated project (`medlink-dockerize-test`, fresh volumes, temporary host-port overrides only to avoid
the running stack) `docker compose --profile full up -d --build` started all five services and the
app's `/actuator/health` returned `"status":"UP"` after 6 s. The project was removed with `down -v`,
leaving the original dev dependencies running.

**Limits:** the existing local Keycloak database volume was created by PostgreSQL 16 while `master`
now pins the Postgres 18 image; that separate dev-stack failure is #74 (a fresh volume works, as the
clean run shows). The check covers startup and health, not a browser login or a Keycloak admin
operation.

## MLink-113 — one compose setup per workflow, GitHub #52 (2026-10-18, fix/52-one-compose-setup)

**Decision:** keep two files, one job each, one command each (README §1 table).
- `compose.yaml`, coding: Postgres, Keycloak + its DB, Mailpit with fixed dev-only values that match
  `application-dev.yml`; the API runs on the host. `SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run`
  starts it through Spring Boot's compose integration, now pinned to this file
  (`spring.docker.compose.file=compose.yaml`, before: discovery by file name).
- `docker-compose.yml`, full stack and staging/prod: the same four services + API image + Prometheus,
  every secret required from `.env`.
- Not a base file + overrides: compose reads `.env` by itself, so the generated secrets would reach the
  dev mode, whose values must stay those of `application-dev.yml`. The setup scripts already name each file.

**Drift removed** (in `compose.yaml`): Postgres had no named volume, so every setup run (`down`, `up`)
gave the app an empty database while Keycloak kept its users; now `pgdata` like the full stack. Healthchecks
for Postgres, Keycloak's DB and Keycloak; `KC_HOSTNAME_BACKCHANNEL_DYNAMIC` and `KC_METRICS_ENABLED`.
Still different on purpose: the secrets, Postgres's host port (any free one in dev, the integration hands
it to the app), the `org.springframework.boot.ignore` labels.

**Proof:** `ComposeFilesTest` (plain test, no API change): the full stack = the dev services + `app` +
`prometheus`; shared services have the same image, command, container ports, mounts, healthcheck,
dependencies and environment keys; every value but a required secret equals the full stack's default; both
files declare their named volumes; the integration is pinned. Committed red (3 failures), then green;
mutation check: a different `KC_HOSTNAME` default in `docker-compose.yml` turns it red. `docker compose
config` resolves both files. Setup script on Windows, dev mode and `-FullStack`: 0 failures, 0 warnings.
`setup-dev.sh --skip-tests` (dev mode) in Git Bash 5.3 on Windows: 0 failures, 0 warnings. Not run on macOS
(bash 3.2) or Linux, and not in `--full-stack` mode: no change to the scripts, so this stays a gap.

**For everyone:** dev Postgres data now survives a setup run. README §1 has a table of what keeps and what
deletes the dev volumes. After checking out a branch with other migrations, re-run with `-Reset` if Flyway
refuses to start.

**Found, not fixed here (the scripts are shared files):** the two scripts write the realm stamp with a
different hash case (PowerShell `Get-FileHash` upper case, `sha256sum` lower case). On one clone used
with both scripts the stamps never match, so every switch of script wipes the dev volumes (the realm is
saved first). One user runs one script, so it only bites when both are used on one machine.

## MLink-115 — audit-log query API

**Built:** staff-only `GET /api/admin/audit-entries`, with inclusive ISO-8601 `from`/`to` timestamps,
`userId` and `action` filters. Results are sorted newest first (timestamp, then id) and returned as
`items` plus `page`, `size`, `totalElements` and `totalPages`; page size defaults to 50 and is capped at
100. Invalid date ranges, page values, page sizes and action names return `400 VALIDATION_FAILED`.
The query service only reads from the append-only audit repository.

**Proof:** `AuditQueryFunctionalTest` checks all filters together, inclusive date bounds, stable ordering,
pagination, invalid parameters and actions; `AccessMatrixFunctionalTest` verifies anonymous `401`,
patient/practitioner `403`, and staff access. `.\mvnw -B "-Dtest=AuditQueryFunctionalTest,AuditFunctionalTest,AccessMatrixFunctionalTest" test`:
**101 tests, 0 failures**.

## MLink-59 — audit trail, every refusal recorded (2026-10-17, epic/MLink-6)

**Built:** migration **V7** `audit_entries` (id, timestamp, user_id, action, resource_type, resource_id,
ip_address, outcome; index on (user_id, timestamp); no foreign key on `user_id`, so a row outlives its
user) with triggers that reject `UPDATE`, `DELETE` and `TRUNCATE` from any role: ordinary SQL cannot
change or delete a row (MLink-17). The table's owner can still drop or disable the triggers, and today the
owner is also the runtime role (see Limits). `model/AuditEntry` (no
setters, every column `updatable = false`), `AuditAction` (`ACCESS_DENIED`), `AuditOutcome` (`DENIED`),
`repository/AuditEntryRepository` (a plain `Repository`: save and read only), `service/AuditService.record`
(finds the caller through `CurrentUser.idOrNull()` and the IP itself; own transaction, `REQUIRES_NEW`).
`idOrNull()` never throws: an identity that is not a Keycloak id is logged (never the name) and recorded
with no user id, so an event is never lost. One call point: `security/JsonAccessDeniedHandler`, which sees
every 403 (before and inside controllers), records `ACCESS_DENIED` / `DENIED` / `REQUEST` / "METHOD /path".
No body and no query string; in the path, a segment is kept only when it is a literal word of one of the
API's routes (`security/RouteWords`, read once from Spring's request mappings at startup) or a UUID;
anything else (an email, a name, a word no route has like `john-smith`) becomes `{redacted}`, then 255
characters at most. So no
password, token, personal or medical value reaches the trail, and the targeted id stays for an
investigation. A failure to write is logged and the 403 still goes out. `UserControllerTest`'s fake users
now have UUID names like real ones.

**Proof:** `AuditFunctionalTest` (real Keycloak, real database), committed red first (5336661): a refusal
writes exactly one row with the caller, the IP, the action and the outcome; an anonymous refusal (no
CSRF header) writes a row with no user; raw SQL `UPDATE` and `DELETE` on the trail are refused. Mutation
checks: without the `record` call, the two row tests turn red; without the triggers, the append-only test
turns red. After the code owner's review (PR #56), committed red first (a852e64): an identity that is not
a Keycloak id is still recorded (`AuditIdentityTest`, MockMvc: a real login cannot produce it); an email
in a path is stored as `{redacted}`; an id in the path is kept; a very long path is cut to 255 characters
and still answers 403. Second review, committed red first (2dece17): a route-shaped word that no route has
(`john-smith`) is redacted; a long path of one-letter segments becomes `{redacted}` segments. Mutation
checks: throwing again on a non-UUID identity, removing the redaction, or accepting any lowercase word as
a route word, turns a test red. `./mvnw -B clean test -Dsurefire.runOrder=reversealphabetical`: **140 tests, 0
failures**, and no audit error in the logs.

**Limits:** append-only holds against ordinary SQL, not against the table's owner: the owner can drop or
disable the triggers, and today Flyway and the application run as the same database role. Protecting the
trail from a compromised application role needs separate roles (an owner for migrations, a runtime role
with only `INSERT` and `SELECT` on `audit_entries`); it touches compose, the environment, the setup
scripts and Terraform, so it is a separate GitHub issue. **Closed 2026-11-17 by GitHub #57 (MLink-165):** the API
runs as `medlink_app`, which can only append to and read the trail (see the MLink-165 section). Behind a reverse proxy the recorded IP is the proxy's until forwarded headers are configured
(`server.forward-headers-strategy`, with the proxy trusted). Anonymous refusals are recorded with no
rate limit, so a flood of forged writes grows the table (per-IP limits: GitHub #51). Reading the trail
(MLink-33) and account operations (MLink-72) are other tasks; they add their own `AuditAction` values.

## MLink-114 — audit sensitive actions

**Built:** `AuditAction` now includes `LOGIN`, `ACCOUNT_UPDATED` and `DATA_READ`; `AuditOutcome`
includes `SUCCESS` and `FAILURE`. `UserService` records successful user-list and single-user reads
(`USER` / `ALL` or the user's UUID). `UserProvisioningService` records a successful account update
when it synchronizes an existing user's email or username from Keycloak. `LoginService` records
successful and refused login attempts; where Keycloak refuses credentials before identifying a user,
the resource id is the fixed value `UNKNOWN`, never the submitted email. No migration or endpoint was
added.

**Proof:** `SensitiveActionsAuditFunctionalTest` checks read events, successful login events and
failed attempts without storing the submitted email. `ConsoleUserProvisioningFunctionalTest` checks
that an email synchronized from Keycloak creates an `ACCOUNT_UPDATED` / `SUCCESS` audit entry.
`.\mvnw.cmd -B test "-Dsurefire.runOrder=reversealphabetical"`: **546 tests, 0 failures, 0 errors**.

**Scope:** the existing API has no general account-edit endpoint; the update event covers the
existing account synchronization path. A user deletion is not mislabeled as an account update.

## MLink-58 — current user and ownership check (2026-10-17, epic/MLink-6)

**Built:** `security/CurrentUser` (the caller's id is the Keycloak id the login saved in the session, =
`users.id`; their row; their roles) and `security/OwnershipGuard.check(ownerId)` (owner or
`ADMINISTRATIVE_STAFF` pass, anyone else gets an `AccessDeniedException`). `GET /api/users/{id}` checks
**before** reading, so a stranger gets the same 403 whether the id exists or not; staff get 404 for an
unknown id. Every refusal is 403 `{code: "FORBIDDEN"}` in our error body, from one place:
`security/JsonAccessDeniedHandler`, wired in `config/SecurityConfig`. It answers refusals before any
controller (a write without the CSRF header) and inside them (`@PreAuthorize`, `OwnershipGuard`), which
used to get Spring Boot's error body without a `code`.

**Proof:** `OwnershipFunctionalTest` (real Keycloak), committed red first (ab8b553): a patient reads their
own record (200) but not another user's (403, nothing of theirs in the body); same 403 for an unknown id;
staff read any record and get 404 for an unknown one; a role refusal and a missing CSRF header are 403 JSON
`FORBIDDEN`. Mutation checks: without the ownership check, or without the JSON handler, a test turns red.
A second handler in `GlobalExceptionHandler` survived its mutation check (refusals inside controllers
reach the security handler anyway), so it was removed. `./mvnw -B clean test
-Dsurefire.runOrder=reversealphabetical`: **132 tests, 0 failures**.

**Limits:** "un praticien n'accède qu'aux patients qui lui sont rattachés" is not in this task (no link
yet; records work). Refusals are not audited yet (MLink-59).

## MLink-86 — session expiry (2026-10-16, epic/MLink-6)

**Built:** the "not logged in" answer is our JSON error: 401 `{code: "UNAUTHENTICATED"}` for no session,
an unknown or ended session id, and a session Tomcat expired after 15 idle minutes
(`security/JsonAuthenticationEntryPoint`, one line in `config/SecurityConfig`). It was an empty 401.
React shows its login page on it (MLink-85); `SESSION_EXPIRED` stays the answer when Keycloak ends an
open session (#28).

**Proof:** `SessionExpiryFunctionalTest` (real Keycloak), committed red first (3e36d1c): the session the
login opens has `maxInactiveInterval` 900 s (read from Tomcat); a session expired the way Tomcat does
after 15 idle minutes (`session.expire()`), an unknown session id and a session ended by logout all get
401 JSON, never a redirect. No real 15-minute wait, and no shorter timeout in the test: all functional
tests share one Spring context on a fixed port, and Tomcat sweeps expired sessions once a minute.
Mutation check: the old empty 401 turns the 3 JSON tests red.
`./mvnw -B clean test -Dsurefire.runOrder=reversealphabetical`: **126 tests, 0 failures**.

## MLink-54 — logout (2026-10-16, epic/MLink-6)

**Built:** `POST /api/auth/logout` (endpoint 7): `controller/LogoutController` → `security/LoginSessions.close`
(reads the refresh token kept at login, invalidates the session, clears the security context, sends
`JSESSIONID` with `Max-Age=0`) → `LoginService.logout` → `KeycloakLoginClient.endSession` (best effort: a
Keycloak failure is only logged, the answer is still 204). No change to `SecurityConfig`: the endpoint
needs a session (401 without) and the CSRF header (403 without) like every write.

**Proof:** `LogoutFunctionalTest` (real Keycloak), committed red first (142e9b0): 204 then the session no
longer works; no Keycloak session left; session cookie expired; no CSRF header → 403 and the session
survives; no session → 401. Mutation checks: skipping the Keycloak logout, or the expired cookie, turns a
test red. "Keycloak down → still 204": `KeycloakLoginClientTest.aFailedLogoutIsOnlyLogged`.
`./mvnw -B clean test -Dsurefire.runOrder=reversealphabetical`: **122 tests, 0 failures**.

**Limits:** the tests check the session with `/api/users/_health`: `/api/auth/me` (MLink-53) is not built yet.
The Postman collection does not cover login and logout yet.

## MLink-55 — login through the API, and MLink-65 (sub-sprint 2 part) (2026-10-15)

**Built** (branch `feat/ss2-login`, on top of #38):
- `POST /api/auth/login` (endpoint 5): `controller/LoginController` → `service/LoginService` →
  `service/KeycloakLoginClient` (password grant as `medlink-bff`; tokens checked: JWKS signature,
  issuer, `azp`) → `security/LoginSessions` (new session id, `ROLE_*` from our three roles only,
  tokens stored as an `OAuth2AuthorizedClient` so `SessionValidityFilter` keeps working, #28).
  `UserProvisioningService.provision(...)` runs at every login (#29).
- An unverified email is refused (403) and the Keycloak session the grant opened is ended; same
  when provisioning refuses (409 `ACCOUNT_CONFLICT`).
- The redirect login is gone: `oauth2Login`, `ProvisioningOidcUserService` and
  `KeycloakRolesMapper` removed; `medlink-bff` standard flow OFF in the realm; not logged in →
  401 everywhere. `Browser.login` in the functional tests uses the endpoint.
- MLink-65 (sub-sprint 2 part): both setup scripts log in as `patient.dev` through the endpoint
  (wrong password 401 first, then 200 and a call with the session); the PKCE redirect check is gone.

**Proof**
- `LoginFunctionalTest` (real Keycloak), committed red first (be2c1f8): verified patient 200 with
  only `PATIENT`, no token in the body or cookies, new session id at each login, wrong password
  and unknown email identical 401, unverified 403 with no API or Keycloak session left, disabled
  403, no CSRF header 403. Mutation checks: accepting unverified emails, skipping the Keycloak
  logout, keeping Keycloak's built-in roles: each turns a test red.
- `KeycloakLoginClientTest` (Keycloak faked): no answer, 5xx, `invalid_client`, incomplete token
  answer → 503; a failed Keycloak logout is only logged.
- The #28/#29 functional tests and the lockout tests pass through the new login.
- `./mvnw -B clean test -Dsurefire.runOrder=reversealphabetical`: **109 tests, 0 failures**.
  Setup script (Windows): 0 warnings; the bash login block run alone against the dev stack: same result.
- Keycloak refuses the old redirect for `medlink-bff` ("Standard flow is disabled for the client").

**Limits**
- Locked account: 401 like a wrong password, decided on MLink-55 (2026-10-16); proved by
  `aLockedAccountGetsTheSameAnswerAsAWrongPassword`.
- Practitioner and staff get a session at login for now: the code step (`mfaRequired`) is sub-sprint 3.
- The setup scripts check the session with `/api/users/_health`: `/api/auth/me` (MLink-53) is not built yet.
- The realm checks "redirect URI" and "PKCE S256 enforced" still pass but no longer mean anything
  (standard flow is off): to remove with Mohamed, with `redirectUris`/PKCE in the realm file.
- `setup-dev.sh` was not run as a whole (Windows); its login block was.

## Code owner's review on #32 — fixes (2026-10-14)

**Built** (branch `fix/MLink-79-78-review`):
- **No account enumeration (MLink-79):** register answers 201 with the same message whether or not the email is
  taken; a taken email (in our database or only in Keycloak) creates nothing and the owner gets a French warning
  mail, at most one per address per 15 minutes (`MailThrottle`, `app.mail.warning-interval`). `EmailTakenException`
  is internal, no longer an `ApiException`. The contract in plan.md §2 and §4 is updated.
- **A patient is a user (MLink-78):** `Patient extends User`, JPA `JOINED` inheritance; same tables, no migration.
- **Race fixed** in `UserProvisioningService` (#29 code): a first login racing with itself no longer reads as an
  email conflict (found by the suite in CI's order under load).
- **Functional tests get a mail server:** Mailpit next to Keycloak; `SignupFunctionalTest` proves sign-up → mail →
  link → `emailVerified` in Keycloak (the answer to review comment 3, no code change needed).
- Postman collection updated: the three former 409 requests now expect the normal 201; folder 2 checks one
  verification mail and one warning.

**Review comments:** 1 (enumeration) and 4 (extend User) agreed and done; 3 (verify for real) answered with the
proof; 2 (disable instead of delete for a failed sign-up) waits for the code owner's answer on the trade-off.

**Proof:** functional tests red first (33d5f6e), then green; mutation check: without the throttle, 3 retries send 3
warnings. `./mvnw test`: **102 tests, 0 failures** in both class orders. Setup script: 0 failures. Newman: 147
assertions, 0 failures.

**Limits:** the throttle is in memory (one API instance). A taken email answers a little faster than a new one
(no Keycloak call): a timing difference, not an answer difference; per-IP rate limits at the proxy (decision page
Q8) blunt it.

## Sub-sprint 2 — realm: lockout, event log, idle timeout, password grant (2026-10-14)

**Built** (branch `feat/MLink-66-67-realm`, realm file + both setup scripts):
- MLink-66: 5 failed logins lock the account for **15 minutes** (`waitIncrementSeconds` 60 → 900, `permanentLockout`
  false); during the lock even the right password is refused.
- MLink-67: Keycloak's event log on for 30 days (`LOGIN`, `LOGIN_ERROR`, `LOGOUT`, `USER_DISABLED_BY_TEMPORARY_LOCKOUT`,
  `UPDATE_PASSWORD`, `UPDATE_TOTP`, `VERIFY_EMAIL`), each line with user and IP; admin events with details.
- MLink-83: a session is over after **15 minutes without a call**, on both sides: `server.servlet.session.timeout=15m`
  (every profile, each call counts as activity) and the realm's `ssoSessionIdleTimeout` 1800 → 900 (max stays 10 h).
  The expiry test itself is MLink-86. **Story MLink-22 says "le jeton JWT est renouvelé": the browser holds no JWT.**
  The rolling server session does that job, and the API renews Keycloak's token itself (#28). The criterion should be
  reworded to "la session est prolongée à chaque action".
- For MLink-55 (asked by Youssef): `medlink-bff` may do the **password grant** (direct grants ON). The redirect flow
  stays ON until MLink-55 removes `oauth2Login` in the same PR.

**Proof**
- Functional tests against the real Keycloak, red first (4c9ecb2): not locked after 4 wrong passwords, locked at the
  5th, then the right password refused; realm lockout settings; 5+ `LOGIN_ERROR` lines with the IP and one lockout
  line; events and admin events configured; password grant works for `medlink-bff`, refused with a wrong secret and
  for the admin service account. Mutation check: `failureFactor` 6 turns the lockout test red.
- Dev stack after the automatic re-import: the setup script's 3 new checks pass, 0 failures. By hand: 5 wrong
  passwords → the right one refused (`invalid_grant`), brute-force status `disabled: true`, event log shows each
  `LOGIN_ERROR` with IP `172.22.0.1` and the `USER_DISABLED_BY_TEMPORARY_LOCKOUT` line.
- `./mvnw test`: **97 tests, 0 failures**, in both class orders.

**Limits**
- Keycloak's quick-login guard also locks an account for **1 minute after 2 failures less than 1 second apart**
  (scripts, not people). Stricter, not weaker; scripted checks (MLink-68) must wait more than a second between tries.
- The events live in Keycloak, not in our `audit_entries` table (MLink-59): bringing them over is not planned this
  sprint.
- Everyone's dev realm is re-imported once by the setup script (realm backup taken automatically).

## GitHub issues #28 and #29 — done ahead of sub-sprint 2 (2026-10-14)

**Built** (branch `fix/issues-28-29`, on top of sub-sprint 1):
- **#29** `service/UserProvisioningService`: exactly one `users` row per Keycloak account, created at first
  login for accounts made outside the API (console, realm import); follows an email or username changed in
  Keycloak; two first logins at once still make one row; an email held by another account is refused (409
  `ACCOUNT_CONFLICT`, nothing overwritten). Today's redirect login calls it through
  `security/ProvisioningOidcUserService`; **sub-sprint 2's login endpoint must call `provision(...)` too.**
- **#28** Keycloak tokens moved into the HTTP session; `security/SessionValidityFilter` renews the access token
  when it expires and ends our session (401 `SESSION_EXPIRED`) when Keycloak refuses: account disabled, signed
  out in Keycloak, password reset, SSO session over. Keycloak unreachable: 503, session kept. A disabled user is
  out within 5 minutes; no session outlives the 10-hour SSO maximum. **Sub-sprint 2 keeps this as long as the
  new login stores its tokens as an `OAuth2AuthorizedClient` in the session** (the filter reads them there).
- `exception/ErrorBody`: the one error shape, shared by the exception handler and the filter.

**Proof**
- Functional tests (`src/test/java/.../functional/`): the real API on a real port, a real Keycloak (same image
  and realm file as dev), a real database; the test logs in through the Keycloak form like a browser and acts in
  the Keycloak console through its admin API. Committed red first (6bdea0d), green after: console user gets one
  row, email change followed, email conflict refused; disabled user and signed-out user lose their open session,
  an active user keeps it across renewals.
- `UserProvisioningServiceTest` (real database): concurrent first logins make one row (mutation check: without
  the duplicate-key handling it fails); `SessionValidityFilterTest`: the branches a shared Keycloak cannot give on
  demand (unreachable → 503, no tokens → 401).
- `./mvnw test`: **83 tests, 0 failures**. Setup script: 0 failures (practitioner.dev now gets its row at the
  setup login). Postman collection: 154/154.

**Limits**
- Ending sessions **at once** when staff disable an account is still sub-sprint 5 (MLink-70, session registry);
  today it takes up to 5 minutes.
- The 15-minute idle timeout (MLink-83) is still sub-sprint 2: it needs the realm change and the cookie work.
- The functional tests start a Keycloak container (about 30 s, once per run); CI's `build-test` pulls the image.
- The test browser uses its own cookie jar: Java's `CookieManager` files `localhost` cookies under
  `localhost.local` and never sends them back (Keycloak then refuses its login form with 400).

## Sub-sprint 1 — registration and email verification (2026-10-14)

**Built** (branch `feat/ss1-registration`, one commit per task):

| Task | What |
|---|---|
| MLink-75 | Client `medlink-admin` (service account, no login flow) with only `manage-users`, `view-users`, `query-users`, `query-groups`; secret `KC_ADMIN_CLIENT_SECRET` in compose, the three `.env` examples, `keycloak.admin.*`. Setup scripts generate it, **append keys missing from an existing `.env`**, and check the account |
| MLink-76 | `service/KeycloakAdminService` (interface) + `RestKeycloakAdminService`: `createUser`, `setPassword`, `deleteUser`, `setEmailVerified`, `getUser`; token cached until 30 s before expiry, a 401 retries once; 5 s / 10 s timeouts. `ApiException` and its subclasses carry status, `code`, French message |
| MLink-78 | V4 `patients` (id = `users.id`), `model/Patient` with `@OneToOne @MapsId User`, `PatientRepository` |
| MLink-77 | V5 `email_tokens` (SHA-256 only, 24 h, single use), `EmailTokenService`, `MailService` (French text + HTML), `EmailVerificationService`; endpoints 3 and 4; Spring Mail; Mailpit `v1.27.10` in both compose files |
| MLink-82 | `RegistrationControllerTest`, committed red before the code (401, username too short), green after |
| MLink-79 | V6 `users.username` → 254; `RegisterRequest`, `PatientMapper`, `RegistrationService`; endpoint 2 |
| MLink-53 | Endpoint 1 `GET /api/auth/csrf`; `X-XSRF-TOKEN` added to the CORS allowed headers. `/me` is sub-sprint 2 |

**Proof**
- `./mvnw -B verify` through the setup script: **69 tests, 0 failures** (16 Keycloak client with
  `MockRestServiceServer`, 6 tokens, 2 patients, 12 verify/resend, 13 register, 3 CSRF/CORS, plus the existing ones).
- `scripts/setup-dev.ps1`: **0 failures, 0 warnings**, including two new checks:
  `service account medlink-admin: can list users, refused on realm settings (clients)` and
  `signup works end to end: register, mail in Mailpit, its link verifies the account` (the smoke
  account is deleted afterwards).
- By hand against the dev stack: CSRF 204 → register 201 → same email 409 `EMAIL_TAKEN` → weak
  password 400 `PASSWORD_POLICY` "Le mot de passe doit contenir au moins 1 caractère spécial…" (its
  Keycloak user deleted) → French mail in Mailpit → link 204, again 410 → Keycloak
  `emailVerified: true` → `users` and `patients` rows under the Keycloak id, token stored as 64 hex
  characters → the password appears 0 times in the app log.
- Postman collection `docs/postman/MedLink-sprint2-ss1.postman_collection.json` (endpoints 1–4 and their edge
  cases, 45 requests) run with Newman against the dev stack: **154 assertions, 0 failures**. Its first run found
  a real bug the MockMvc tests could not see: a refused CSRF token (or a 415) answered **401** to a real client,
  because the forward to `/error` needed a login. Fixed (ERROR dispatch permitted) with a regression test through
  a real port.
- GitHub issue #31 (cross-origin signup): `CrossOriginSignupTest` proves signup from the configured origin
  (201, CORS headers), CSRF still required from it (403), and another origin refused on the preflight and
  on the request itself, even with a valid CSRF token (403, nothing created). Mutation check: allowing the
  other origin turns the two refusal tests red.
- The realm backup before a wipe ran for real (first run of this branch re-imported the realm):
  `.dev/realm-backups/realm-backup-20261014-163344.json` holds the previous realm.

**Decisions** (also in the log below)
- Phone and address are optional at signup; names and birth date are required.
- Errors: `details` is one entry per field; a database error answers 500 `INTERNAL_ERROR` (logged
  server-side, no SQL to the client); malformed JSON answers 400 `VALIDATION_FAILED`.
- `spring.jpa.hibernate.ddl-auto=validate` in `application.properties`: tests now check the
  entities against the migrations too.
- Verify-email consumes the token and calls Keycloak in one transaction, so a Keycloak failure
  leaves the link usable.

**Limits**
- `scripts/setup-dev.sh` got the same changes as the PowerShell script but was only checked with
  `bash -n`: it cannot run on the lead's Windows machine (no `lsof`/`ss` in Git Bash). First real
  run: a teammate on macOS/Linux.
- `-FullStack` mode (`docker-compose.yml`: app image, Mailpit as `mailpit`, the new secret) was not
  run; `docker compose config` resolves it.
- "Add the route to the matrix of task 16.4" (MLink-82) is not done: that matrix arrives with
  MLink-16 in sub-sprint 5.
- MLink-82 is Ahlem's task; the tests were written in this session. Worth a read by her.
- Patient fields are plain columns: encryption at rest is MLink-14 / MLink-24.
- No per-IP rate limit on register or resend (resend is limited per account, one mail a minute).
- The verification mail is sent in the request (synchronous): simple, but a slow SMTP slows the
  signup answer. Revisit if production SMTP is slow.

- **Open question (issue #31): how production serves React and the API.** React reads the `XSRF-TOKEN`
  cookie, so it must run on the same host as the API (a proxy sending `/api` to Spring: recommended for a
  BFF, no CORS in production) or the cookie needs a parent domain. `.env.prod.example` shows two hosts
  (`medlink.example.com`, `api.medlink.example.com`): with them as they are, signup would fail in
  production. Dev works (cookies ignore the port). To decide before the first deployment.

**Next step:** sub-sprint 2 (login and session) in a new session, once this PR is merged.

## MLink-57 — lock `/api/users` (2026-10-14, ahead of sub-sprint 5)

**Built:** `@EnableMethodSecurity` in `config/SecurityConfig`; `security/Roles` holds the three
role names (no role typed as a string elsewhere); `GET /api/users` and `DELETE /api/users/{id}`
are `ADMINISTRATIVE_STAFF` only (`@PreAuthorize`). `anyRequest().authenticated()` is unchanged.
Both setup scripts' login check now calls `/api/users/_health`, since practitioner.dev gets 403
on the list.

**Proof:** `UserControllerTest`: patient and practitioner get 403 on list and delete, staff gets
200 / 204, anonymous gets 401 (the 4 non-staff cases failed before the change). `./mvnw -B verify`:
18 tests, 0 failures. Setup script: 0 warnings.

**Why early:** any logged-in user, a patient included, could list every user and delete any of
them. MLink-57 needs nothing and blocks MLink-58 and MLink-60.

**Limits:** `GET /api/users/{id}` stays open to any logged-in user until MLink-58 (self or staff).
`DELETE` removes our row only, not the Keycloak account.

## Sub-sprint 0 — the plan (2026-10-14)

**Built:** `CLAUDE.md`, this folder (`README.md`, `plan.md`, `report.md`); README and
ARCHITECTURE point to the backend-only rule. YouTrack: 25 of MLink-51..90 rewritten for the
backend-only design (each starts with a "Re-mapped 2026-10-14" line), MLink-91 added
(change-password endpoint), and a note on each affected story (MLink-15, 18, 19, 20, 21, 22, 23).
The four skin tasks became React pages: MLink-52 login, 64 code screens, 80 verify-email, 89 password.

**Decisions** (see the log below).

**Limits:** the guide PDF in `Docs/` was updated to v1.2 outside the repository.

## Decisions log

| Date | Decision | Why | Rejected |
|---|---|---|---|
| 2026-10-21 | Appointment tables take V9, after V8 `rate_limits` | The code owner settled it on PR #60: the PR merged first holds the lower number, or Flyway refuses the older one on databases that already ran the newer (MLink-97) | "Whoever merges second renumbers" |
| 2026-10-21 | Practitioners' first and last names live in `practitioners`, like patients' in `patients` | The slot search shows them without asking Keycloak; only Ela's code changes (MLink-97) | Names moved to `users` (touches `Patient` and registration); asking Keycloak per result |
| 2026-10-24 | Slot search input: at most 31 days per search (exactly 31 accepted) and 1–50 slots per page; one 400 lists every broken field; "no result" message only when nothing matches | Bounds the work one request can ask of the database (a multi-year or million-row search is a cheap denial of service); a patient looks a few weeks ahead; React shows each error under its field (MLink-100) | No bound; one error at a time; a message on every empty page |
| 2026-10-24 | Unreadable search parameters answered by an `@ExceptionHandler` inside `SlotController` | Our error body with the field's name, without changing the shared `GlobalExceptionHandler` for every other endpoint (MLink-100) | A global handler (affects the whole team's endpoints, to be agreed first); Spring's default 400 body |
| 2026-10-23 | A practitioner's slots never overlap: exclusion constraint on `tstzrange(starts_at, ends_at, '[)')` per practitioner, with `btree_gist` | Code owner's second review on #87: a unique start time lets 09:00–10:00 and 09:30–10:00 coexist; `[)` keeps back-to-back slots valid; the database holds the rule for every writer (MLink-97) | A check in the service only; per-clinic scope (one person cannot be in two clinics at once) |
| 2026-10-23 | Slot search (`GET /api/slots`): patients only; a past `from` starts at now; earliest first, paged 20 by default; the practitioner shown by id and names only | Patients book, staff and practitioners have their own screens later; a started slot cannot be booked; no personal data beyond what a patient needs (MLink-98) | Staff allowed to search; an error for a past `from` |
| 2026-10-23 | `from`/`to` of the slot search checked in the controller, after `@PreAuthorize`, not as required parameters | Spring rejects a missing required parameter before method security runs: a practitioner without dates would get 400, hiding the 403 the access matrix expects (MLink-98) | `@RequestParam` required (400 before the role check); a URL rule in the shared `SecurityConfig` |
| 2026-10-22 | One active appointment per slot is a database rule: partial unique index `appointments(slot_id) WHERE status = 'BOOKED'` in V9 | Code owner's review on #87: the slot status alone does not stop two racing bookings; cancelled appointments must not block the slot (MLink-97) | Leaving it to the booking service (MLink-103) only |
| 2026-10-22 | Dev practitioners are imported in dev only, by a one-shot `compose.yaml` service (`keycloak-dev-users`, Keycloak partial import), from `keycloak/dev/`; the shared realm file holds none of them | Code owner's review on #87: the shared realm reaches staging and prod, and "delete them by hand" is not a security boundary; one container works the same on Windows and Linux and leaves the setup scripts unchanged (MLink-97) | Importing them from the two setup scripts (two copies of the logic, the Windows one untestable without Docker); keeping them in the realm and deleting them after deployment |
| 2026-10-21 | Dev data for appointments: a repeatable SQL migration in `db/dev-seed`, enabled by the `dev` profile only, for dev practitioners with fixed Keycloak ids | Tests and prod never see it; the seeded rows and the Keycloak users share the same ids, so the practitioners log in onto them (MLink-97) | A Java seeder that asks Keycloak for the ids (more code, needs Keycloak at startup); practitioners with generated ids (forbidden: a user id comes from Keycloak) |
| 2026-10-23 | A password change ends every session of the user, this one included; the user logs in again | A change made because a session may be compromised must close it; same ending as the forced change (review on #89) | Keeping the current session only (more calls, more partial failures); keeping all |
| 2026-10-23 | Old sessions are refused by the API itself (`session_revocations`, set before the password changes), not only by Keycloak's sign-out | Fail closed: a Keycloak failure after the change can no longer leave a stolen session open; the browser only reaches Keycloak through the API (review on #89) | 204 + an error log when Keycloak's sign-out fails (left a window until Keycloak's idle timeout); 503 after a change that did happen (the old password cannot be put back) |
| 2026-10-23 | Login with a Keycloak required action other than `UPDATE_PASSWORD` alone: 403 `ACCOUNT_SETUP_INCOMPLETE` | The API finishes only the forced change; starting it would leave the account blocked after the change; Keycloak checked the password first, so it reveals nothing | Running the change and leaving the user locked out; 401 (looks like a wrong password) |
| 2026-10-20 | The role × route matrix asserts past security / 401 / 403, not exact statuses, and reads the routes from Spring | It proves access rules only, so a feature's answers (200, 400, 404) can change without touching it; a route missing from the matrix fails the build (MLink-60) | Exact status per cell; a hand-kept list of routes |
| 2026-10-19 | The login keeps the OAuth password grant, as a consciously accepted exception: [decision record 0001](../../decisions/0001-login-password-grant.md) | Keeps "the frontend never talks to Keycloak"; decided by the plan owner and the code owner on PR #58 (GitHub #50); its conditions: per-IP rate limiting (done, #60) and this link | Authorization Code + PKCE with a themed Keycloak login page |
| 2026-10-18 | Rate limits counted in Postgres (`rate_limits`, one atomic upsert per hit), not Redis | Shared by every instance and race-free with the database we already run; no new service to deploy and secure (GitHub #51) | Redis; the in-memory map (one instance, racy) |
| 2026-10-18 | Per-IP limit past the threshold → 429 `TOO_MANY_REQUESTS` with `Retry-After`; per-account limits stay silent (201 / 202) and login per account stays Keycloak's lockout | A count keyed by the IP says nothing about an account, so a 429 is safe; a per-account 429 would tell which emails exist | The usual answer when limited per IP (hides an outage of the form from real users); our own per-account login lock |
| 2026-10-18 | `X-Forwarded-For` believed only from `TRUSTED_PROXIES` (loopback by default) | Anyone can send the header; trusting it from everyone lets a client pick its own IP and escape every limit | Spring Boot's default list (all private ranges, which includes Docker's bridge) |
| 2026-10-17 | The audit trail is append-only in the database: triggers reject `UPDATE`, `DELETE` and `TRUNCATE` on `audit_entries`; against the table's owner it needs separate roles (GitHub issue) | "Ni modifié ni supprimé depuis l'application" (MLink-17) must hold for raw SQL too; the Java side alone is a promise; the owner boundary is stated, not hidden (review on #56) | A save-and-read-only repository only |
| 2026-10-17 | A refusal records "METHOD /path" with the path redacted by allowlist (the API's own route words, read from Spring at startup, and UUIDs kept; anything else `{redacted}`), never the body or the query string | No password, token, personal or medical value can reach the trail, whatever a client puts in a path; the targeted id stays for investigations (review on #56) | Logging the whole request; the raw path |
| 2026-10-17 | An identity that is not a Keycloak id is recorded with no user id, never dropped | A security trail must not lose events silently (review on #56) | Failing the write (the event was lost) |
| 2026-10-17 | One audit call point for refusals: `JsonAccessDeniedHandler` | It sees every 403, before and inside controllers (MLink-58); a failed write never turns a 403 into a 500 | A call in each controller, or in two handlers as the ticket said |
| 2026-10-17 | Ownership is checked before the data is read: a non-owner gets 403 whether the id exists or not; only staff get 404 | A 404 next to a 403 tells anyone which ids exist | Reading first (404 for unknown ids to everyone) |
| 2026-10-17 | One JSON access-denied handler for every 403 (`JsonAccessDeniedHandler`), none in `GlobalExceptionHandler` | Refusals inside controllers reach the security handler anyway; one place for the message; a second handler survived its mutation check | A handler in both places (as the ticket said) |
| 2026-10-16 | Not logged in → 401 JSON `UNAUTHENTICATED` (our error body), never an empty 401 or a redirect | React needs a code to show its login page; same body shape everywhere | Spring's empty 401 (`HttpStatusEntryPoint`) |
| 2026-10-16 | Session expiry tested by expiring the real Tomcat session, not by a short timeout | One shared Spring context on a fixed port; Tomcat sweeps expired sessions once a minute | Overriding `server.servlet.session.timeout` to 1 minute in the test |
| 2026-10-16 | A locked account answers 401 `INVALID_CREDENTIALS`, identical to a wrong password; the message mentions a possible lock | A 423 tells anyone whether an email has an account (Keycloak never locks an unknown one); decided on MLink-55 | 423 `ACCOUNT_LOCKED` |
| 2026-10-15 | Login: a separate `LoginController` at `/api/auth` | No conflict with the sign-up changes in `AuthBffController` (#39) | Adding the endpoint to `AuthBffController` |
| 2026-10-15 | Keep only `PATIENT`, `PRACTITIONER`, `ADMINISTRATIVE_STAFF` from the `roles` claim | Keycloak adds built-in roles that mean nothing to MedLink | Every claimed role as `ROLE_*` (the old `KeycloakRolesMapper`) |
| 2026-10-15 | An unverified login ends the Keycloak session it opened | Keycloak accepts unverified emails; no session anywhere for a refused login | Leaving it to expire |
| 2026-10-15 | Setup scripts: wrong password first, then the right one | A successful login resets Keycloak's failure count, so repeated runs never lock `patient.dev` | Only the right password; the right one first |
| 2026-10-14 | Register never reveals a taken email: same 201, the owner is warned by mail (one per 15 min) | Code owner's review: never expose users' data; a stranger cannot test which emails have an account | 409 `EMAIL_TAKEN` (the story's original wording) |
| 2026-10-14 | `Patient extends User` (JPA JOINED), same tables | Code owner's review; the lead confirmed a person holds one role only | A patient *has* a user (`@MapsId`), kept for "one login, several profiles", which will not happen |
| 2026-10-14 | #28 and #29 fixed now on the redirect login, through pieces sub-sprint 2 reuses (provisioning service, tokens as an `OAuth2AuthorizedClient` in the session) | Open security issues; the reusable parts are most of the work | Waiting for sub-sprint 2; a background sync from Keycloak |
| 2026-10-14 | Session validity by token renewal: a refused renewal ends the session | No extra call while the token is fresh; Keycloak decides (disabled, signed out, reset, SSO over) | Calling Keycloak on every request; a status column of our own |
| 2026-10-14 | Functional tests against a real Keycloak container for login-related work | Asked by the team: prove the behaviour end to end, not a mock | Mocked Keycloak only |
| 2026-10-14 | Signup: phone and address optional; first name, last name, birth date required | A patient can complete the profile later; identity fields are needed from the start | Every field required |
| 2026-10-14 | Keycloak admin token: our own small client-credentials cache in `RestKeycloakAdminService` | The task asked for a cache testable with `MockRestServiceServer` (two calls, one token) | Spring Security's `OAuth2ClientHttpRequestInterceptor` |
| 2026-10-14 | Service account check probes `GET /admin/realms/medlink/clients` (403) | `GET` on the realm answers 200 with a short summary to any admin, so it proves nothing | Probing the realm itself |
| 2026-10-14 | The verification mail is sent synchronously after the commit; a failure is only logged | Simple first; the user can ask for a new link | A queue or `@Async` |
| 2026-10-14 | Database errors answer 500 `INTERNAL_ERROR` in our error shape | Same body for React everywhere; no SQL or values leak | Spring's default error page |
| 2026-10-14 | MLink-57 (lock `/api/users`) done before sub-sprint 5 | Live hole: a patient could list and delete users; the task has no dependency | Waiting for sub-sprint 5 |
| 2026-10-14 | Role checks with `@PreAuthorize` and `security/Roles` constants | One readable rule per endpoint; role names typed once | URL rules per role in `SecurityConfig` |
| 2026-10-14 | The frontend never talks to Keycloak: register, verify, login, code, logout, passwords, accounts all go through the API | Users never see Keycloak; one place to secure | Skinned Keycloak pages (decided 2026-10-11, now superseded) |
| 2026-10-14 | Login = API → Keycloak password grant, server to server, confidential client | Only way to log in without a Keycloak page; tokens never leave the API | Browser redirect to Keycloak |
| 2026-10-14 | Email verification: our token (hashed, 24 h, single use) and our mail; the link opens a React page that POSTs the token | Keycloak's mail links point at Keycloak; POST stops mail scanners from consuming the token | Keycloak's verification mail; a GET link to the API |
| 2026-10-14 | Session cookie only; Keycloak tokens kept in the server session | No token readable by scripts; immediate logout | Returning tokens to React |
| 2026-10-14 | Staff code (TOTP) handled by the API, secret encrypted | Keycloak cannot enrol a code without its own page | Keycloak OTP flow |
| 2026-10-14 | User row created in the same request as the Keycloak user; first-login creation only as a fallback for users made outside the API | The app knows every user at once | Waiting for first login |
| 2026-10-14 | Each sub-sprint runs in a fresh session; these files are the handoff | Keep sessions short and focused | One long session |
| 2026-10-13 | `users.id` is the Keycloak id; `patients.id` = `users.id` (a patient *has* a user, `@MapsId`). **Replaced 2026-10-14**, see above | One id per person | Our own generated id + `keycloak_id` column |
| 2026-10-13 | `users` keeps `id`, `username`, `email`, `created_at` | Asked by the lead | Dropping `username` |
| 2026-10-13 | Controller maps entities to DTOs through `mapper/`; services never see a DTO | Review feedback on PR #22 | Mapping inside services |
| 2026-10-11 | Roles and names follow the class diagram; ids are UUIDs | One vocabulary across diagram, code and report | `ADMIN` / `DOCTOR`, numeric ids |
| 2026-10-11 | Keycloak holds passwords, policy, lockout; the API stores none of them | Proven code instead of ours | Hand-written password storage |
