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
| 3 | `QrCodes`: otpauth URL → PNG data URI | MLink-62 | Done | 7760e68 (red), next commit (green) |
| 4 | `V11__user_mfa.sql`, entity, repository (atomic failure counter) | MLink-62 | To do | |
| 5 | `PendingStep<T>` shared by the two pending steps (refactor) | MLink-62 | To do | |
| 6 | Staff branch of login + test helper | MLink-62 | To do | |
| 7 | `POST /api/auth/mfa/setup` | MLink-62 | To do | |
| 8 | `POST /api/auth/mfa/verify` | MLink-63 | To do | |
| 9 | Postman (folder 6 + folder 8) and docs | MLink-62/63 | To do | |
| 10 | Checks and PR | MLink-62/63 | To do | |

**Next step:** before subtask 4, check the open PRs again for Flyway numbers and post the #87 comment (on Mohamed’s go); then subtask 4 (`V11__user_mfa.sql`).

## YouTrack moves (Mohamed does them: Claude only reads YouTrack)

| When | Move |
|---|---|
| Subtask 0 | MLink-62 → *In Progress* |
| Subtask 8 starts | MLink-63 → *In Progress* |
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
