# Sprint 2 report

Updated by every sub-sprint PR. Newest information first inside each section.

## Status

| Sub-sprint | Scope | State | PR | Date |
|---|---|---|---|---|
| 0 | Sprint plan, backend-only design, YouTrack re-mapped | Done | docs/sprint-2-plan | 2026-10-14 |
| 1 | Registration and email verification (endpoints 1–4) | Done; review fixes in [#39](https://github.com/assali-mohamed-ali/api-MedLink/pull/39) | [#32](https://github.com/assali-mohamed-ali/api-MedLink/pull/32) | 2026-10-14 |
| 2 | Login and session (endpoints 5–7) | **In progress, split** (see below) | [#38](https://github.com/assali-mohamed-ali/api-MedLink/pull/38) | — |
| 3 | Staff code, TOTP (endpoints 8–9) | To do | — | — |
| 4 | Passwords (endpoints 10–11) | To do | — | — |
| 5 | Accounts and access (endpoints 12–16) | To do | — | — |

### Where we are (handoff, 2026-10-15)

Merged on `master`: sub-sprint 1 (#32), staff-only `/api/users` (#33, MLink-57), GitHub issues
#28/#29 (#35) and their plan (#34). Issues #27–#31 are closed.

**Open PRs, waiting for the code owner** (merge in this order):
1. [#37](https://github.com/assali-mohamed-ali/api-MedLink/pull/37): `master` is red on CI (`CsrfEndpointTest`);
   this fixes it (test only). All checks green.
2. [#38](https://github.com/assali-mohamed-ali/api-MedLink/pull/38): realm: lockout 15 min after 5 failures
   (MLink-66), event log (MLink-67), 15-minute idle (MLink-83), password grant for `medlink-bff` (needed
   by MLink-55). All checks green. It contains #37's commit too.
3. [#39](https://github.com/assali-mohamed-ali/api-MedLink/pull/39): the code owner's review on #32: no account
   enumeration on register (MLink-79), `Patient extends User` (MLink-78), a race fix. Stacked on #38:
   after #38 merges, change its base to `master` so CI runs fully, then merge.
- Still open on #32: review comment 2 ("disable instead of delete" for a failed sign-up), waiting for
  the code owner's answer on the trade-off.
- Ask the repo admin: make `build-test` a required check in the ruleset; delete merged remote
  branches and turn on auto-delete.

**Sub-sprint 2 split** (agreed on MLink-55):
- **Youssef:** MLink-55 login (branch `feat/ss2-login`, in progress), then MLink-54 logout and MLink-86
  session expiry test. He also removes `oauth2Login`, turns the realm's redirect flow OFF in the same
  PR, and updates the setup scripts' login check (MLink-65).
- **Mohamed:** MLink-66, 67, 83 (in #38), MLink-84 cookie hardening (**after MLink-55**: it needs the
  login to change the session id and the redirect login to be gone).
- **Ahlem:** React pages, MLink-68 scripted lockout check (attempts more than 1 s apart, see the realm
  section), MLink-53 `/me`.

**Next tickets for Mohamed** (his own, in this order):
1. MLink-87 password policy (history + French messages): does not depend on the login. It changes
   the realm file, so everyone's dev realm is re-imported once.
2. MLink-84, once MLink-55 is merged.
3. Sub-sprint 3 (staff code): MLink-62, MLink-63, after MLink-55.
4. Sub-sprint 4 (passwords): MLink-91, MLink-88, MLink-90, after MLink-55.

## Where the code stands (start of sprint 2)

- Merged on `master` (PR #22): roles `PATIENT` / `PRACTITIONER` / `ADMINISTRATIVE_STAFF`;
  `users.id` = Keycloak id (V3); controller maps through `mapper/UserMapper`, service returns
  entities; one-command setup scripts for Windows and macOS/Linux with automatic realm
  re-import and realm backup; Postgres pinned to 16.
- Still from the earlier design, replaced in sub-sprint 2: login by redirect to Keycloak
  (`oauth2Login` in `config/SecurityConfig`), dev users' rows created nowhere yet.
- On the lead's machine the local database applied an earlier V3: run the setup script with
  `-Reset` once.

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
- Locked account answers 401 like a wrong password: 423 is a pending decision (see plan.md §4,
  endpoint 5). `attack-detection` works with `medlink-admin` if 423 is kept.
- Practitioner and staff get a session at login for now: the code step (`mfaRequired`) is sub-sprint 3.
- The setup scripts check the session with `/api/users/_health`: `/api/auth/me` (MLink-53) is not built yet.
- The realm checks "redirect URI" and "PKCE S256 enforced" still pass but no longer mean anything
  (standard flow is off): to remove with Mohamed, with `redirectUris`/PKCE in the realm file.
- `setup-dev.sh` was not run as a whole (Windows); its login block was.

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
| 2026-10-15 | Login: a separate `LoginController` at `/api/auth` | No conflict with the sign-up changes in `AuthBffController` (#39) | Adding the endpoint to `AuthBffController` |
| 2026-10-15 | Keep only `PATIENT`, `PRACTITIONER`, `ADMINISTRATIVE_STAFF` from the `roles` claim | Keycloak adds built-in roles that mean nothing to MedLink | Every claimed role as `ROLE_*` (the old `KeycloakRolesMapper`) |
| 2026-10-15 | An unverified login ends the Keycloak session it opened | Keycloak accepts unverified emails; no session anywhere for a refused login | Leaving it to expire |
| 2026-10-15 | Setup scripts: wrong password first, then the right one | A successful login resets Keycloak's failure count, so repeated runs never lock `patient.dev` | Only the right password; the right one first |
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
| 2026-10-13 | `users.id` is the Keycloak id; `patients.id` = `users.id` (`@MapsId`, a patient *has* a user) | One id per person; one login may hold several profiles later | Our own generated id + `keycloak_id` column; JPA inheritance |
| 2026-10-13 | `users` keeps `id`, `username`, `email`, `created_at` | Asked by the lead | Dropping `username` |
| 2026-10-13 | Controller maps entities to DTOs through `mapper/`; services never see a DTO | Review feedback on PR #22 | Mapping inside services |
| 2026-10-11 | Roles and names follow the class diagram; ids are UUIDs | One vocabulary across diagram, code and report | `ADMIN` / `DOCTOR`, numeric ids |
| 2026-10-11 | Keycloak holds passwords, policy, lockout; the API stores none of them | Proven code instead of ours | Hand-written password storage |
