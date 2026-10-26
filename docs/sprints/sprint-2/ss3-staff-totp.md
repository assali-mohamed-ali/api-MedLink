# Sub-sprint 3: staff code (TOTP), MLink-62 and MLink-63: plan and progress

**Handoff file.** A new session reads this first, then `report.md` Status. Update **Progress** and the
**Session log** at the end of every subtask, and commit them with the work. The design below was agreed with
Mohamed on 2026-10-24; changing it needs his OK, recorded in the log.

Branch `feat/ss3-staff-totp`, based on `master`. One PR to `master` at the end (subtask 10).

## Progress

| # | Subtask | Ticket | State | Commits |
|---|---|---|---|---|
| 0 | Handoff file, branch, coordination comments | — | Done; comments postponed to subtasks 4 and 6 (Mohamed, 2026-10-25) | 5c13854 |
| 1 | `Totp`: RFC 6238 maths | MLink-62 | Done | 0ca2232 (red), c038f55 (green) |
| 2 | `MfaSecretCipher`: AES-256-GCM, key from env | MLink-62 | Done (class; key wiring in subtask 6) | b89af4e (red), a5f1952 (green) |
| 3 | `QrCodes`: otpauth URL → PNG data URI | MLink-62 | Done | 7760e68 (red), 7535327 (green) |
| 4 | `V11__user_mfa.sql`, `UserMfa` record, `UserMfaStore` (atomic counters) | MLink-62 | Done | f1b90db (red), f82669e (green) |
| 5 | `PendingStep<T>` shared by the two pending steps (refactor) + `Clock` bean | MLink-62 | Done | 1655a4d (red), d366288 (green) |
| 6a | Key wiring: cipher bean, `APP_MFA_ENCRYPTION_KEY` (config, compose, env, Terraform, setup scripts, docs) | MLink-62 | Done | 897ef4b (red), e744ce8 (green) |
| 6b | `MfaService`: enrol, verify, lock (no HTTP yet) | MLink-62/63 | Done | d1853b0 (red), dae733d (green) |
| 7 | HTTP, in one green commit: staff branch of login + `MfaPending` + endpoints 8 and 9 + `Browser.login()` helper | MLink-62/63 | Done (regrouping OK'd by Mohamed) | 9398f1b (red), abe58dc (green); idle-time fix e914835 (red), 0c100e6 (green) |
| 9 | Postman (folder 6 + folder 8) and docs | MLink-62/63 | Done | a720111 (Postman); log of an unreadable secret cd4ecb7 (red), 0370c1c (green); docs 97351d5 |
| 10 | Checks and PR | MLink-62/63 | **In progress**: manual check done; gitleaks, local CI checks, PR left | |

**Next step (new session):** subtask 10. (1) `git fetch` and check `master` moved (merge it in if so, re-run
both test orders); (2) gitleaks on the whole branch (`zricethezav/gitleaks:v8.28.0`, config
`security/.gitleaks.toml`); (3) the other CI checks locally (Terraform fmt/validate in Docker, `check-prod-config.sh`,
actionlint if a workflow changed: none did); (4) open the PR to `master` with the template: AI box checked, the
"relu" box left for Mohamed, real outputs in "Preuves", the deploy note (`APP_MFA_ENCRYPTION_KEY` in staging/prod
`DEPLOY_ENV_FILE` before merge); (5) give Mohamed the YouTrack moves and the comments below.

## YouTrack moves (Mohamed does them: Claude only reads YouTrack)

| When | Move |
|---|---|
| Subtask 0 | MLink-62 → *In Progress* |
| Subtask 6b (the code check's rules start) | MLink-63 → *In Progress* |
| PR opened | MLink-62, MLink-63 → *To Verify*, with the PR link |
| PR merged | MLink-62, MLink-63 → *Done* |

## Session log

- **2026-10-24:** plan agreed; branch created from `master` `d7b0585`; `master` green (243 tests, 0 failures,
  both class orders). CI on GitHub is blocked by billing (jobs not started); proposal for a local runner posted
  on #91, waiting for the code owner. Flyway: `master` has V9, #87 holds V10 → we take **V11**. Postman: `master`
  has folders 0–6, #87 adds 7 → we take **8**.
- **2026-10-25:** decided with Mohamed: subtasks 1–3 first (new standalone classes: no schema, no shared file, no
  teammate affected); the #87/#90 comments are posted just before subtasks 4 and 6, after checking the open PRs
  again. Subtask 2 builds only the cipher class and its unit test; the key wiring (`application-dev.yml`, setup
  scripts, compose, `FunctionalTestBase`) moves to subtask 6, when the cipher becomes a bean.
- **2026-10-25:** subtask 1 done. `Totp` (own RFC 6238 with `javax.crypto`, Base32 per RFC 4648, otpauth URL):
  27 unit tests on the published vectors. A stub let the red commit compile. Mutation checks: truncation mask
  `0x07` → 5 failures; tolerance 2 → 6 failures.
- **2026-10-25:** subtask 2 done. `MfaSecretCipher` (AES-256-GCM, new 12-byte IV per write, user id as associated
  data, key checked in the constructor and never echoed): 14 unit tests. A plain class for now, not a bean: the
  application does not need the key until subtask 6. Mutation checks: no associated data → only
  `aSecretCopiedToAnotherUserDoesNotDecrypt` fails; fixed IV → only `eachEncryptionUsesANewIv` fails.
- **2026-10-25:** subtask 3 done. `QrCodes`: ZXing `core` 3.5.3 (no transitive dependency) draws a 240 px QR
  code, error correction M, the JDK writes the PNG; 3 tests read it back with ZXing. Mutation: encoding another
  text → only `scanningTheQrCodeGivesBackTheUrl` fails. No open PR touched `pom.xml`. New open PR **#93**
  (Chourouk, MLink-115 audit log API) also edits `AccessMatrixFunctionalTest` and `report.md`: expect small
  merge conflicts there in subtasks 7–9.
- **2026-10-25:** #92 review fixed (a6f9f04: figures dated to `d7b0585`, PDF no longer committed); branch
  `feat/ss3-staff-totp` pushed as a backup (no PR); V11 and folder 8 re-checked and announced on #87
  (https://github.com/assali-mohamed-ali/api-MedLink/pull/87#issuecomment-6397863639).
- **2026-10-25:** subtask 4 done. **Change from the plan:** a `JdbcClient` store (`repository/UserMfaStore`) and a
  `model/UserMfa` record instead of a JPA entity + repository, like `SessionRevocationStore` and `RateLimitStore`:
  every rule is one SQL statement (`saveSecret` never replaces an enabled secret; `markUsed` refuses a used or
  older step and any code while locked; `registerFailure` counts, locks at the max, freezes while locked and
  restarts after). Limits and times come from the caller. 15 tests (Testcontainers), including 10 wrong codes
  at once (exactly 1..5, then 5) and the same code twice at once (works once). Mutation checks: no replay guard,
  code accepted while locked, counting while locked: each turns its tests red.
- **2026-10-25:** #91 (DevSecOps: pre-commit hooks, CI steps) merged on `master` (`5b3def7`); merged into this
  branch (`7aad48c`, no conflict), full suite 302/302, pushed. Our files pass the trailing-whitespace and
  final-newline hooks. The code owner has not answered the local-runner proposal on #91.
- **2026-10-25:** subtask 5 done. `security/PendingStep<T>`: start (drops the old session and login), current
  (ends the session once 5 min have run out), clear; the attribute is the user class name (a Spring proxy keeps
  it). `PasswordChangePending` is now a 3-line subclass; `PasswordController` calls `current`. New `config/ClockConfig`
  (`Clock.systemUTC()`), used by the pending steps and later by `MfaService`. **Change from the plan:** a unit test
  `PendingStepTest` (7, fake clock) instead of none, because expiry cannot be waited for in a functional test.
  Safety net unchanged and green: `PasswordChangeFunctionalTest` 7, `ForcePasswordResetFunctionalTest` 5,
  `LoginFunctionalTest` 8. Mutation checks: no expiry, old session kept: each turns its test red.
- **2026-10-25 (evening):** coordination posted. #90:
  https://github.com/assali-mohamed-ali/api-MedLink/pull/90#issuecomment-6402788305. Findings there: Chourouk's
  `loggedInStaff()` uses `apiLogin()` and would get 401 on `/api/users` once staff need a code (asked her to use
  `login()`; if our PR merges second we change it), and her `LOGIN` SUCCESS audit runs after the password alone
  (proposed: for staff, after the code, in a follow-up). MLink-65 comment posted by Mohamed (which script lines
  change). New open PR **#94** (MLink-122, management port) also edits `SecurityConfig`, `application.properties`,
  `DEPLOYMENT.md`, `threat-model.md`: other lines, whoever merges second rebases.
- **2026-10-25 (evening):** subtask 6a done (key wiring). The setup scripts' `new_secret` gives 24 bytes in hex, so a
  `new_key` / `New-Key` (32 bytes, base64) is added, also used when an existing `.env` lacks the key. Every test
  context gets a test key from `src/test/resources/config/application.properties` (instead of a `registry.add` in
  `FunctionalTestBase`: the other `@SpringBootTest` classes need it too). Dev values carry `# gitleaks:allow`
  (base64 cannot hold the allow-listed `dev-only-`). Terraform `fmt -check` and `validate` pass (1.9.8 in Docker);
  gitleaks on the changed files: only `master`'s reviewed `KC_DB_URL` line. 3 tests (`MfaKeyStartupTest`);
  mutation: binding another property name → red. Full suite 312/312. **For the PR:** staging and prod deploys
  need `APP_MFA_ENCRYPTION_KEY` in `DEPLOY_ENV_FILE` before merge (compose refuses to start without it).
- **2026-10-25 (evening):** subtask 6b done. `MfaService` (`startEnrolment`, `verify`, `isEnrolled`), 8 tests on a real
  database with a movable clock (shared `MovableClock`, also used by `PendingStepTest`). The store's atomic
  statements are the only lock check (an extra early check could not be caught by any test, so it was dropped).
  Mutations: 6 wrong codes instead of 5, a 14-minute lock, the lock ignored: each turns its tests red.
  **Regrouping proposed (needs Mohamed's OK):** subtasks 6–8 as planned cannot each end green. Once login answers
  `mfaRequired` for staff, every existing staff and practitioner test needs `Browser.login()` to finish the code
  step, which needs endpoints 8 and 9. So the HTTP part (login branch, `MfaPending`, both endpoints, helper,
  `MfaFunctionalTest`) is one subtask 7: red commit(s) first, then one green commit, every commit before it green.
  Open point for 7: a secret that no longer decrypts (key changed) gives a 500 today; to decide (re-enrol?).
- **2026-10-25 (night):** Mohamed OK'd the regrouping and keeping the 500 for an undecryptable secret (a key change
  is an operations mistake; re-enrolment would come with an admin reset, later). Subtask 7 done:
  - `LoginResult.MfaRequired`, returned for `PRACTITIONER`/`ADMINISTRATIVE_STAFF` just before `Success`;
    `security/MfaPending extends PendingStep<Login>`; `LoginResponse.mfaRequired(setupRequired)`;
    `LoginSessions.open` removes both pending attributes.
  - `controller/MfaController`: `/setup` (`MfaMapper` builds the QR code) and `/verify`. The pending step is
    checked **before** the code format, so a caller without one gets 401 whatever it sends (like endpoint 10);
    that is why the 6-digit check is `MfaVerifyRequest.isSixDigits()` in the controller, not `@Valid`. On 423
    the pending step is cleared and the Keycloak session ended. `SecurityConfig` permits both routes.
  - Tests: `MfaFunctionalTest` 19 (every contract line of endpoints 5-staff, 8 and 9). `FunctionalTestBase`:
    a shifted clock (`CLOCK`, the API's `Clock` bean in functional tests) so each staff login uses a fresh
    30 s code and a test can let the 15-min lock and the 5-min step run out; `Browser.login()` does the code
    step; console users in a group. Matrix: 2 lines (L for every caller), 57 cells; Matrix and Ownership log in
    through `login()`.
  - Mutations: lock not ending the login, no 6-digit check, a full session keeping the pending step: each
    turns its tests red.
  - **Finding (not our ticket):** the realm's `defaultGroups: ["/patients"]` adds every console-made user to
    `/patients`, so a practitioner made in the console also gets `PATIENT` (CLAUDE.md: one role only). The test
    console removes it, as an admin must today. To report to the team (realm file owner).
  - **Bug found and fixed after the green commit:** the code step shortens its session's idle time to 5 minutes
    (`PendingStep`), and the full session opened in it after the code kept 5 instead of 15 (MLink-86). Red test
    `theSessionOpenedByTheCodeLastsFifteenIdleMinutesNotFive` (300 instead of 900), then `LoginSessions.open`
    sets `server.servlet.session.timeout` again. Full suite 348/348 in both class orders. Pushed.
- **2026-10-25 (late):** subtask 9 done.
  - **Postman** (a720111): one collection-level pre-request script computes `{{totpCode}}` from `{{totpSecret}}`
    (RFC 6238 with CryptoJS HmacSHA1). Folder 6's staff step and folder 8 use disposable users made in the
    Keycloak console (default `/patients` group taken off): `staff.dev` may hold a code from an earlier manual
    run, whose secret the collection cannot know. Folder 6 needs its own admin token (folder 4's expires during
    folder 5's 61 s wait). The file is hand-formatted, so the change was spliced in (8 lines removed, all meant).
    newman 6.2.1 on the dev stack (API from this branch on 8080, `.dev/start-dev.ps1`): 101 requests, 240
    assertions, 0 failures, twice in a row. Running folders 6 and 8 alone fails by design (folder 6 reuses
    folder 4's account): run the whole collection.
  - **The agreed 500 for an unreadable secret** had no log naming the user (no generic handler): red test
    (`OutputCaptureExtension`), then `MfaService` logs the user and the likely cause, never the secret.
  - **Docs:** plan.md §4 (endpoints 8–9: errors, lock per user across logins, 400 after the step check) and §5
    (V11 columns, the required key); report.md (Status, dated note, MLink-62/63 section); README (test users,
    staff code, endpoint rows); ARCHITECTURE (login section, ISO rows); threat model (S2 ✅, priority list).
  - Full suite **349/349** in both class orders.
- **2026-10-25 (night):** **manual check passed.** Mohamed logged in as `practitioner.dev` through Swagger, scanned
  the QR code with Google Authenticator and entered the code: 200 and a session. The first codes were refused
  (`INVALID_CODE`) because **the phone's clock was off**; the server's was right (checked against an HTTP `Date`
  header). Found by computing, from the stored secret and the dev key, the codes the server accepted at that minute
  and comparing (codes printed, never the secret). Added to README troubleshooting (`INVALID_CODE`, `MFA_LOCKED`).
  `practitioner.dev` is now enrolled on Mohamed's phone in the dev database.
- **Open items carried to the new session:**
  - Comments to post after merge (Claude writes, Mohamed posts on YouTrack): MLink-64 (Ahlem: contract + error
    codes, the QR is a data URI, codes are 6 digits, `MFA_LOCKED` lasts 15 min), MLink-65 (Youssef: the staff
    code check in the setup scripts now possible; `practitioner.dev` needs a code, the scripts cannot compute it
    without a secret: suggest a disposable console user like Postman does), #87 and #90 ("it is on `master`").
  - The realm's `defaultGroups: ["/patients"]` finding: draft a comment once Mohamed says where (realm owner).
  - Follow-up after #90 merges: MFA audit entries and `LOGIN` SUCCESS after the code for staff.
  - **#92 (report v2), code owner 2026-10-26:** rewrite the PR's commits so `report-v2.pdf` (`47c91bc`) never
    enters history, update to current `master`, re-run the checks. Needs a force-push of
    `docs/sprint-2-report-v2`: only with Mohamed's go. CI failures there are runner allocation (billing), not tests.
  - #91 (local runner proposal): still unanswered. The untracked
    `docs/sprints/sprint-2/MedLink-sprint2-report.pdf` must not be committed (Mohamed moves it).

## Coordination comments (post on Mohamed's go, then note the link here)

**On #87 (Ela):**
> Sub-sprint 3 (staff TOTP, MLink-62/63) takes Flyway **V11** (`V11__user_mfa.sql`) and Postman folder **8**, after
> your V10 and folder 7. Heads-up: once it lands, practitioners and staff answer the login with `mfaRequired`
> and get no session until they enter a code. `FunctionalTestBase.Browser.login()` will do the code step by
> itself, so tests that log in with `login()` keep working; `apiLogin()` stays the raw answer.

**On #90 (Chourouk):**
> Sub-sprint 3 (staff TOTP, MLink-62/63) also changes `LoginService`: a new `LoginResult.MfaRequired` and one branch
> just before `return new LoginResult.Success(...)` for practitioners and staff, plus `LoginController`. Whoever
> merges second rebases; the overlap is small. Once it lands, staff and practitioners get `mfaRequired` and no
> session at login: `Browser.login()` will complete the code step, but a test that asserts on `apiLogin()` for
> `staff.dev` (like `SensitiveActionsAuditFunctionalTest`) will see `{ "mfaRequired": true }`. MFA audit entries
> will come after your PR, on top of your `LOGIN` audit.

## Plan (agreed 2026-10-24)

### Context
Today, practitioners and staff get a full session as soon as Keycloak accepts their password
(`LoginService.login` → `LoginResult.Success`). Plan.md §3/§4 asks the API to handle a second factor
(decision 2026-10-14: Keycloak cannot enrol a code without its own page):
- login answers `{mfaRequired, mfaSetupRequired}` and keeps a 5-minute pending step;
- endpoint 8 enrols an encrypted TOTP secret;
- endpoint 9 checks the code and opens the session.

The tickets are Mohamed's: MLink-62 and MLink-63. MLink-65 (setup scripts) belongs to Youssef and
MLink-64 (React) to Ahlem; we only tell them what changes.

**Decisions taken with Mohamed (2026-10-24):**
- TOTP: our own RFC 6238 code (`javax.crypto`), plus ZXing `core` for the QR code (PNG written with the JDK).
- Wrong codes are counted per user in the database. 5 wrong codes lock the code step for 15 min, across
  logins and parallel requests. This is stronger than plan.md's "pending step dropped", so plan.md gets updated.
- Teammates: `FunctionalTestBase.Browser.login()` completes the code step on its own. Before merge, post a
  heads-up on #87, #90 and MLink-64.
- MFA audit entries come in a follow-up after #90 (Chourouk) merges. This PR only logs, never the secret or the code.

**Rules for every subtask:**
- **TDD:** the test is written first and committed red (`test:`), then made green (`feat:`/`refactor:`),
  then refactored while green. Each commit body names the YouTrack id. Once per rule, check that breaking
  the rule turns a test red.
- **SOLID/DRY, kept simple (CLAUDE.md: simplest design that meets the contract):**
  - One responsibility per class: TOTP maths, encryption, QR rendering, persistence, flow and HTTP stay separate.
  - Time comes from an injected `java.time.Clock`, never `Instant.now()` inside logic.
  - The two pending steps share one base class instead of a copy.
  - Tests reuse the production `Totp` instead of a second implementation.
  - Layers follow CLAUDE.md: the controller validates and maps through `mapper/`, services take and return
    entities or plain values. No interface unless there are two implementations.

### Subtask 0: handoff file and setup (no code)
**Needs Mohamed's go.**
- Run `git switch master && git pull --ff-only`, then create branch `feat/ss3-staff-totp`.
- Copy this plan to **`docs/sprints/sprint-2/ss3-staff-totp.md`** with a **Progress** checklist (subtasks 1–10).
  Every session ticks the checklist, writes the last commit, the next step and any surprise, and commits it
  with the work.
- Point to that file from `report.md` Status (row 3: "In progress, see ss3-staff-totp.md").
- Commit `docs: sub-sprint 3 plan and progress (MLink-62, MLink-63)`.
- Post the comments. Their text goes in the handoff file, and each is posted only after Mohamed's go:
  - on #87: we take Flyway **V11** (#87 holds V10) and Postman folder **8** (#87 holds 7);
  - on #90: we only add a branch before `return new LoginResult.Success` and a new `LoginResult.MfaRequired`.
    Whoever merges second rebases. Ask whether their staff tests use `login()` (it keeps working) or
    `apiLogin()` (it will see `mfaRequired`).
- YouTrack move for Mohamed (Claude is read-only there): MLink-62 → *In Progress*.

### Subtask 1: `Totp`, the RFC 6238 maths (MLink-62)
- **Red:** `service/TotpTest`:
  - the RFC 6238 SHA1 test vectors, 6 digits;
  - Base32 round trip;
  - ±1 step accepted, ±2 refused;
  - a non-numeric code or one of the wrong length is refused;
  - `matchingStep` returns the step that matched.
- **Green:** `service/Totp.java`, a final class with no Spring and no state:
  - `newSecret()` (20 bytes from `SecureRandom`), `base32(bytes)`, `code(secret, step)`;
  - `OptionalLong matchingStep(secret, code, Instant now)` with a constant-time compare;
  - `otpauthUrl(email, base32Secret)`.
- **Done when:** green, and changing the truncation offset turns a vector red.

### Subtask 2: `MfaSecretCipher`, AES-256-GCM (MLink-62)
- **Red:** `service/MfaSecretCipherTest`:
  - round trip;
  - each encryption differs (random IV);
  - decryption fails with another user id (AAD) or a tampered byte;
  - a key that's missing or not 32 bytes is refused at construction.
- **Green:** `service/MfaSecretCipher.java` (`@Component`):
  - the key comes from `app.mfa.encryption-key=${APP_MFA_ENCRYPTION_KEY:}`;
  - stored format: `base64(iv12 || ciphertext+tag)`;
  - the user id is the AAD.
- **Config:**
  - dev fallback in `application-dev.yml` (same pattern as `KEYCLOAK_CLIENT_SECRET`);
  - test value via `registry.add` in `FunctionalTestBase`;
  - `docker-compose.yml`, `.env.example`, `docs/DEPLOYMENT.md`;
  - Terraform only if it lists the app's other secrets;
  - the key is generated by both setup scripts next to `KC_ADMIN_CLIENT_SECRET`. That's a shared file:
    note on MLink-65 which lines change.
- **Done when:** the app refuses to start without a valid key outside dev.

### Subtask 3: `QrCodes` (MLink-62)
- **Red:** `service/QrCodesTest`: the data URI starts with `data:image/png;base64,`, and decoding the PNG
  with ZXing's reader gives back the otpauth URL.
- **Green:** `service/QrCodes.java`, ZXing `QRCodeWriter` → `BufferedImage` → `ImageIO`.
- Pin `com.google.zxing:core` to an exact version in `pom.xml`.

### Subtask 4: data, migration `V11__user_mfa.sql` + entity + repository (MLink-62)
- **Table:** `user_mfa`
  - `user_id uuid` PK, FK to `users` ON DELETE CASCADE;
  - `secret_enc text not null`;
  - `enabled boolean not null default false`;
  - `enrolled_at timestamptz`;
  - `last_used_step bigint` (blocks replays);
  - `failed_attempts int not null default 0`;
  - `locked_until timestamptz`.
- **Red:** `repository/UserMfaRepositoryTest` (Testcontainers, same style as `SessionRevocationStoreTest`):
  - the atomic `registerFailure(userId, max, lockUntil)`: one `UPDATE … RETURNING` that increments and
    sets `locked_until` at the 5th failure;
  - 10 parallel calls never go past 5 or lose a count.
- **Green:** `model/UserMfa.java`, `repository/UserMfaRepository.java`.
- Record the extra columns compared with plan.md §5 in plan.md and in the handoff file.

### Subtask 5: shared pending step (refactor, DRY)
- Extract `security/PendingStep<T>`, the abstract shape that `PasswordChangePending` uses today: drop the old
  session, empty security context, 5-min TTL, one session attribute, `start`, `current`, `clear`.
- `PasswordChangePending` becomes a thin subclass, with no behaviour change.
- **Safety net:** `PasswordChangeFunctionalTest` and `ForcePasswordResetFunctionalTest` stay green before
  and after. It's a `refactor:` commit with no new test.

### Subtask 6: staff branch of login (MLink-62)
- **Red:** `functional/MfaFunctionalTest`:
  - practitioner.dev and staff.dev login → 200 `{mfaRequired:true, mfaSetupRequired:true}`, no session
    (`/api/users/_health` 401);
  - patient login unchanged;
  - `LoginServiceTest` unit cases for the branch.
- **Green:**
  - `LoginResult.MfaRequired(Login, boolean setupRequired)`;
  - in `LoginService`, if the roles hold `PRACTITIONER` or `ADMINISTRATIVE_STAFF` (`security/Roles`),
    return `MfaRequired`, with `setupRequired = !mfa.isEnabled(id)`;
  - `security/MfaPending extends PendingStep<Login>` (it holds the Keycloak tokens, server side only);
  - in `LoginController`, `pending.start(...)` then `LoginResponse.mfaRequired(setupRequired)`;
  - `LoginSessions.open` also removes the MFA attribute.
- **`FunctionalTestBase.Browser.login()`:** on `mfaRequired`, it enrols (the secret is kept per email for
  the run) and posts the current code with the production `Totp`. `apiLogin()` stays raw.
  - **Here, the existing staff and practitioner tests start using it:** `AccessMatrix`, `Ownership`,
    `ForcePasswordReset`, `StaffKeycloakRights`.
  - Update a test only where it asserts the raw login answer.

### Subtask 7: `POST /api/auth/mfa/setup` (MLink-62)
- **Red (functional):**
  - with a pending step → 200 `{otpauthUrl, qrCode, secret}`;
  - without one → 401;
  - after enrolment → 409 `MFA_ALREADY_ENROLLED`;
  - a second setup before verifying replaces the secret;
  - the stored `secret_enc` is not the Base32 secret;
  - `AccessMatrixFunctionalTest` gets its line.
- **Green:**
  - `service/MfaService.startEnrolment(UUID, email)` returns a small record (secret, url);
  - `controller/MfaController`, `dto/MfaSetupResponse`, `mapper/MfaMapper` (the QR code is built through
    `QrCodes`);
  - `SecurityConfig` permits the route (the pending step is the guard, like `/api/auth/password`);
  - logs show only the user id and the outcome.
- **Wrap-up:**
  - commit `feat:` MLink-62;
  - update the handoff file;
  - YouTrack move for Mohamed: MLink-62 stays *In Progress* until the PR opens.

### Subtask 8: `POST /api/auth/mfa/verify` (MLink-63)
- **YouTrack move for Mohamed:** MLink-63 → *In Progress*.
- **Red (functional):**
  - the right code → 200 `{user}` + a session with the right role, and the code is now enabled;
  - the same code again → 401 (replay);
  - a wrong code → 401 `INVALID_CODE`;
  - 5 wrong codes → 423 `MFA_LOCKED`. After that, a 6th try with the right code still gets 423, the pending
    step is gone, and the Keycloak session is ended (checked through the admin API);
  - the lock holds across a new login, and ends after 15 min (`Clock` moved in a unit test);
  - an expired pending step → 401;
  - `@Pattern("\\d{6}")` → 400;
  - a matrix line.
- **Green:**
  - `MfaService.verify(UUID, code)`: locked → 423; the code matches a step greater than `last_used_step` →
    save the step, enable, reset the counter; otherwise `registerFailure` → 401, or 423 at the 5th;
  - in the controller: 200 → `LoginSessions.open` + `LoginMapper.toResponse`; 423 →
    `MfaPending.clear` + `LoginService.logout(refreshToken)`;
  - `dto/MfaVerifyRequest`.
- **Mutation checks:** removing the lock, the replay guard or the AAD each turns a test red.
- Commit `feat:` MLink-63.

### Subtask 9: Postman and docs
- **Postman:**
  - folder 6: the staff.dev step now needs a code;
  - new folder **8 · Staff code (endpoints 8–9)**;
  - TOTP computed in a pre-request script with the sandbox's CryptoJS (HmacSHA1);
  - a disposable staff user per run, so a re-run never hits 409 with an unknown secret;
  - `npx newman@6.2.1 run …` with 0 failures, against the branch on port 8081.
- **Docs:**
  - plan.md §4 endpoint 9 (lock per user, 15 min) and §5 (columns);
  - `report.md`: a MLink-62/63 section (library choice and why, decisions, proofs, limits) and the Status;
  - README / ARCHITECTURE where login is described.

### Subtask 10: PR
- **Checks:**
  - `./mvnw -B clean verify` and `./mvnw -B test -Dsurefire.runOrder=reversealphabetical`, 0 failures;
  - gitleaks on the branch;
  - the other CI checks run locally while billing blocks Actions.
- **Dev stack, by hand:** practitioner.dev scans the QR with a real authenticator app, enters the code and
  gets a session.
- **PR to `master`:**
  - template filled honestly: AI box checked, the "relu" box left for Mohamed;
  - "Preuves" holds the real outputs;
  - ask @assali-mohamed-ali for the review.
- **YouTrack moves for Mohamed:** MLink-62/63 → *To Verify* with the PR link.
- **After merge, comment text ready for:**
  - MLink-65 (Youssef): the staff code check in the setup scripts;
  - MLink-64 (Ahlem): the contract and the error codes;
  - #87 and #90: it's on `master`.

### Verification (whole sub-sprint)
- Both test orders green.
- The functional tests prove every contract line of endpoints 5 (staff branch), 8 and 9, with the right role
  and the wrong role.
- Each guard has had its mutation check.
- newman passes with 0 failures.
- A manual login with an authenticator app works on the dev stack.
- The handoff file shows every subtask ticked and the PR link.
