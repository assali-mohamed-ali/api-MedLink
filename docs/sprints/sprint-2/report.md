# Sprint 2 report

Updated by every sub-sprint PR. Newest information first inside each section.

## Status

| Sub-sprint | Scope | State | PR | Date |
|---|---|---|---|---|
| 0 | Sprint plan, backend-only design, YouTrack re-mapped | Done | docs/sprint-2-plan | 2026-10-14 |
| 1 | Registration and email verification (endpoints 1–4) | Done; review fixes landing (`fix/land-pr-39`) | [#32](https://github.com/assali-mohamed-ali/api-MedLink/pull/32) | 2026-10-14 |
| 2 | Login and session (endpoints 5–7) | **In progress, split** (see below): login done | [#38](https://github.com/assali-mohamed-ali/api-MedLink/pull/38), [#41](https://github.com/assali-mohamed-ali/api-MedLink/pull/41) | — |
| 3 | Staff code, TOTP (endpoints 8–9) | To do | — | — |
| 4 | Passwords (endpoints 10–11) | To do | — | — |
| 5 | Accounts and access (endpoints 12–16) | To do | — | — |

**2026-10-20:** MLink-120 (GitHub #66, part 2): `docs/ARCHITECTURE.md` and `security/threat-model.md` match
`master` (password-grant login, trust boundaries, STRIDE controls mapped to code and tests, ISO 27001 rows, open
work by priority). No code change. **New gap found:** the realm role `ADMINISTRATIVE_STAFF` holds Keycloak's
`manage-users` and `view-realm`, so staff could manage users in Keycloak's console outside the API (threat model
E3); not tracked yet.

**2026-10-19:** MLink-84 (session cookie hardening: SameSite=Strict, Secure in staging/prod) in review (#63): no
migration, no endpoint.

**2026-10-18:** MLink-112 (GitHub #51, shared rate limits, a condition of #58 option 1) merged (#60). **Migration numbers settled (code owner's review, 2026-10-19): V8 is
`rate_limits` (this PR, ready first); Ela's MLink-97 takes V9.** The PR that merges first must hold the lower
number: a database that already applied V9 makes Flyway refuse a V8 that arrives later.

**2026-10-19:** MLink-113 (GitHub #52, one compose setup per workflow) merged (#61): no migration, no endpoint.

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
scripts and Terraform, so it is a separate GitHub issue. Behind a reverse proxy the recorded IP is the proxy's until forwarded headers are configured
(`server.forward-headers-strategy`, with the proxy trusted). Anonymous refusals are recorded with no
rate limit, so a flood of forged writes grows the table (per-IP limits: GitHub #51). Reading the trail
(MLink-33) and account operations (MLink-72) are other tasks; they add their own `AuditAction` values.

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
