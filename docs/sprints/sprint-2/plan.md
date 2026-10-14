# Sprint 2 plan — identity and security, backend-only Keycloak

Version 1.0 · 2026-10-14 · Owner: Mohamed Ben Naima · Team: Mohamed, Youssef Benchaouacha, Ahlem Sakly

## 1. Goal

A patient registers, verifies their email and logs in; a practitioner or staff member logs in
with a password and a 6-digit code; sessions expire after 15 idle minutes; five wrong
passwords lock an account; staff manage accounts; every API call checks the role.
**The browser never sees Keycloak.**

Stories (YouTrack project `MLink`, sprint `sprint2`):

| Story | Title | Sub-sprint |
|---|---|---|
| MLink-21 | Créer un compte patient (+ email verification) | 1 |
| MLink-15 | Connexion | 1 (CSRF), 2 |
| MLink-19 | Protection anti-brute-force | 2 |
| MLink-22 | Expiration de session | 2 |
| MLink-18 | Authentification multifacteur (TOTP) | 3 |
| MLink-23 | Mot de passe robuste | 4 |
| MLink-20 | Gérer les comptes utilisateurs | 5 |
| MLink-16 | Contrôle d'accès par rôle | 5 (and every sub-sprint: role checks per endpoint) |

## 2. Design rules

| Rule | Why |
|---|---|
| **Front → API → Keycloak → API → front.** No Keycloak page, link, mail or token reaches the browser. | Users never see Keycloak; one place to secure and to change. |
| **Login is our endpoint.** The API sends the email and password to Keycloak's token endpoint (password grant), server to server, as the confidential client `medlink-bff`, and keeps the tokens. | The only way to log in without a Keycloak page. Safe here: private network, confidential client, tokens never leave the server. Keycloak still hashes passwords, applies the policy and the lockout. |
| **Admin actions go through one service account** (`medlink-admin`, least rights) and one class, `KeycloakAdminService`. | One audited door to Keycloak's admin API. |
| **Session cookie only** (HttpOnly, SameSite=Strict, Secure outside dev) + `XSRF-TOKEN` cookie and `X-XSRF-TOKEN` header on writes. Session id changed at login. 15 minutes idle. | A script injected in a page cannot steal a token it never sees; logout is immediate. |
| **Our mails, our tokens.** The API sends verification and activation mails (Spring Mail; Mailpit in dev). Tokens: 32 random bytes, stored as SHA-256, single use, 24 h. The link opens a React page that POSTs the token. | Keycloak stays hidden; mail scanners that pre-open links cannot use the token. |
| **Staff code (TOTP) lives in the API**, secret encrypted (AES-256-GCM, key from env). | Keycloak cannot enrol a code without its own page. |
| **One id per person: Keycloak's.** `users.id` = Keycloak id; `patients.id` = `users.id`. The API creates the Keycloak user first, then our rows, in the same request; if our insert fails it deletes the Keycloak user. Users created outside the API (dev import, console) get their row at first login. | Nothing to map; ownership checks are a plain comparison. |
| **Errors** keep today's shape `{timestamp, status, error, message, details}` and gain a stable machine `code` (e.g. `EMAIL_TAKEN`). `message` is French, for the user. | React reacts to `code`, shows `message`. |
| **No account enumeration where it costs nothing:** resend-verification always answers 202; login failures share one message. Register answers 409 on a known email because the story asks for it. | Fewer hints for attackers, same experience for users. |
| **Simple first:** one API instance, in-memory sessions, no Redis. | Spring Session JDBC can replace it when we run two instances. |

## 3. The flows

**Registration and verification (sub-sprint 1)**
1. React `GET /api/auth/csrf`, then `POST /api/auth/register`.
2. API: email lower-cased; refused with 409 if already in `users`.
3. API → Keycloak: create user (username = email, enabled, `emailVerified=false`, group `/patients`) → id.
4. API → Keycloak: set password (policy refusal → 400, Keycloak user deleted).
5. API, one transaction: `users` + `patients` rows with that id + an `email_tokens` row (`VERIFY_EMAIL`). On failure: Keycloak user deleted.
6. API sends the mail: link `${app.frontend-url}/verifier-email?token=…`. A mail failure is logged only; the user can ask for a new link.
7. React page → `POST /api/auth/verify-email {token}` → API → Keycloak `emailVerified=true` → 204.

**Login (sub-sprints 2–4)**
1. React `POST /api/auth/login {email, password}` → API → Keycloak token endpoint (password grant).
2. Keycloak refuses → 401 (wrong credentials), 423 (locked), 403 `ACCOUNT_DISABLED`, or the
   "account not fully set up" answer → `passwordChangeRequired` (sub-sprint 4).
3. Keycloak accepts → API checks the token signature (JWKS) and reads `sub`, email, names,
   `email_verified`, `roles`. Not verified → 403 `EMAIL_NOT_VERIFIED`, no session.
4. Practitioner or staff → no session yet: a 5-minute "pending code" state in the session,
   answer `mfaRequired` (sub-sprint 3). Patient → `users` row ensured, new session id,
   security context saved, tokens kept in the session → 200 with the user.

**Any authenticated call**
Cookie + `X-XSRF-TOKEN` → session found (else 401) → role check (else 403) → ownership check
where the data belongs to someone (else 403) → parameterized SQL only.

## 4. Endpoint contracts

Common: JSON; writes need `X-XSRF-TOKEN`; error body `{timestamp, status, error, code, message, details}`.
**User object** (endpoints 5, 6, 9): `{ "id": "<uuid>", "email", "firstName", "lastName", "roles": ["PATIENT"] }`.

| # | Endpoint | Access | Sub-sprint | YouTrack |
|---|---|---|---|---|
| 1 | `GET /api/auth/csrf` | public | 1 | MLink-53 |
| 2 | `POST /api/auth/register` | public | 1 | MLink-79 |
| 3 | `POST /api/auth/verify-email` | public | 1 | MLink-77 |
| 4 | `POST /api/auth/verify-email/resend` | public | 1 | MLink-77 |
| 5 | `POST /api/auth/login` | public | 2 (+3, +4) | MLink-55 |
| 6 | `GET /api/auth/me` | session | 2 | MLink-53 |
| 7 | `POST /api/auth/logout` | session | 2 | MLink-54 |
| 8 | `POST /api/auth/mfa/setup` | pending code step | 3 | MLink-62 |
| 9 | `POST /api/auth/mfa/verify` | pending code step | 3 | MLink-63 |
| 10 | `POST /api/auth/password` | session, or pending forced change | 4 | MLink-91 |
| 11 | `POST /api/admin/users/{id}/force-password-reset` | staff | 4 | MLink-88 |
| 12 | `GET /api/admin/users` | staff | 5 | MLink-69 |
| 13 | `POST /api/admin/users` | staff | 5 | MLink-69 |
| 14 | `POST /api/auth/account/activate` | public | 5 | MLink-71 |
| 15 | `PUT /api/admin/users/{id}/role` | staff | 5 | MLink-69 |
| 16 | `PATCH /api/admin/users/{id}/status` | staff | 5 | MLink-70 |

### 1 · `GET /api/auth/csrf`
→ **204**, sets the `XSRF-TOKEN` cookie (readable by JavaScript). React calls it once before
its first write (register, login).

### 2 · `POST /api/auth/register`
```json
{ "email": "amel@example.com", "password": "…", "firstName": "Amel", "lastName": "Ben Salah",
  "birthDate": "1990-04-12", "phone": "+216 22 123 456", "address": "12 rue de Carthage, Sousse" }
```
Validation: email ≤ 254 and valid (spaces around it trimmed); password not blank, ≤ 128 (the
policy is Keycloak's); names required, ≤ 100; `birthDate` required, in the past; phone optional,
≤ 30 (digits, spaces, leading `+`); address optional, ≤ 255.
- **201** `{ "message": "Vérifiez votre boîte mail pour activer votre compte." }`
- **400** `VALIDATION_FAILED` (one entry per field in `details`) · `PASSWORD_POLICY` (French rule)
- **409** `EMAIL_TAKEN` "Un compte existe déjà avec cet e-mail."
- **503** `IDENTITY_UNAVAILABLE` (Keycloak unreachable)
Steps: section 3. Never log or return the password.

### 3 · `POST /api/auth/verify-email`
`{ "token": "…" }` → **204** · **400** `INVALID_TOKEN` (unknown) · **410** `TOKEN_EXPIRED` (expired or already used).
Steps: hash → find `VERIFY_EMAIL` token → Keycloak `emailVerified=true` → `used_at = now`.

### 4 · `POST /api/auth/verify-email/resend`
`{ "email": "…" }` → always **202** `{ "message": "Si un compte non vérifié existe pour cet e-mail, un nouveau lien a été envoyé." }`.
Only when the account exists and is not verified: older unused tokens are cancelled, a new
one is mailed. At most one mail per minute per account.

### 5 · `POST /api/auth/login`
`{ "email": "…", "password": "…" }`
- **200** `{ "user": {…} }` + session cookie (patient, or staff after the code step)
- **200** `{ "mfaRequired": true, "mfaSetupRequired": true|false }` — practitioner or staff (sub-sprint 3)
- **200** `{ "passwordChangeRequired": true }` — forced change pending (sub-sprint 4)
- **401** `INVALID_CREDENTIALS` "E-mail ou mot de passe incorrect." (same for unknown email)
- **403** `EMAIL_NOT_VERIFIED` (React offers endpoint 4) · `ACCOUNT_DISABLED`
- **423** `ACCOUNT_LOCKED` "Compte verrouillé pendant 15 minutes."
- **503** `IDENTITY_UNAVAILABLE`
Notes for the implementer: verify against the real Keycloak how a locked account answers the
password grant; if Keycloak hides the lock behind "invalid credentials", ask its
`attack-detection` admin endpoint for that user before answering 401 or 423. No token refresh
is needed: the API does not call Keycloak with the user's token, it only keeps the refresh
token to end the Keycloak session at logout.

### 6 · `GET /api/auth/me`
→ **200** `{ "user": {…} }` from the session · **401**.

### 7 · `POST /api/auth/logout`
→ **204**. The API ends the Keycloak session (logout endpoint with the stored refresh token;
a failure is only logged) and invalidates our session.

### 8 · `POST /api/auth/mfa/setup` (pending code step, first time only)
→ **200** `{ "otpauthUrl": "otpauth://totp/MedLink:…", "qrCode": "data:image/png;base64,…", "secret": "…" }`
· **409** when a code is already enrolled · **401** without a pending step.
Stores the secret encrypted, not yet active; the first valid code at endpoint 9 activates it.

### 9 · `POST /api/auth/mfa/verify` (pending code step)
`{ "code": "123456" }` → **200** `{ "user": {…} }` + session · **401** `INVALID_CODE` ·
**423** `MFA_LOCKED` after 5 wrong codes (the pending step is dropped; log in again).
TOTP: 30 s, 6 digits, one step of tolerance either side.

### 10 · `POST /api/auth/password`
- With a session: `{ "currentPassword", "newPassword" }` → **204** · **401** wrong current · **400** `PASSWORD_POLICY`.
- With a pending forced change (from endpoint 5): `{ "newPassword" }` → **204**; then the user logs in again.
The API checks the current password with Keycloak, sets the new one through the admin API,
and removes the `UPDATE_PASSWORD` action in the forced case. Policy errors are mapped to French.

### 11 · `POST /api/admin/users/{id}/force-password-reset` (staff)
→ **204** · **404**. Adds `UPDATE_PASSWORD` in Keycloak and ends the user's Keycloak sessions.

### 12 · `GET /api/admin/users?search=&page=0&size=20` (staff)
→ **200** `{ "items": [ { "id", "email", "firstName", "lastName", "role", "enabled", "emailVerified" } ], "total": 0 }`.

### 13 · `POST /api/admin/users` (staff)
`{ "email", "firstName", "lastName", "role": "PRACTITIONER" | "ADMINISTRATIVE_STAFF" }`
→ **201** user · **409** `EMAIL_TAKEN` · **400**. `PATIENT` is refused here (patients register).
Keycloak user (no password, group by role) **and our `users` row at once**, then an
activation mail: `${app.frontend-url}/activer-compte?token=…` (`ACTIVATE_ACCOUNT` token, 24 h).

### 14 · `POST /api/auth/account/activate`
`{ "token", "password" }` → **204** · **400** `INVALID_TOKEN` / `PASSWORD_POLICY` · **410** `TOKEN_EXPIRED`.
Sets the first password and `emailVerified=true`. The code is enrolled at first login (endpoint 8).

### 15 · `PUT /api/admin/users/{id}/role` (staff)
`{ "role" }` → **204** · **404**. Moves the user to the role's Keycloak group.

### 16 · `PATCH /api/admin/users/{id}/status` (staff)
`{ "enabled": false }` → **204** · **404** · **409** when staff disable themselves.
Disabling ends the user's Keycloak sessions. Known limit: their current API session lives
until its 15-minute idle timeout (closing it needs a session registry).

Existing `/api/users` endpoints become staff-only in sub-sprint 5.

### React pages the mails and flows rely on (Ahlem)
`/inscription`, `/verifier-email?token=`, `/connexion`, `/double-authentification`,
`/mot-de-passe`, `/activer-compte?token=`, `/admin/comptes`. Base URL: property `app.frontend-url`.

## 5. Data and configuration changes

| Sub-sprint | Database (next free `V<N>`) | Keycloak realm file | Configuration |
|---|---|---|---|
| 1 | `users.username` → `varchar(254)`; `patients(id PK/FK users, first_name, last_name, birth_date, phone, address, created_at)`; `email_tokens(id, user_id FK, purpose VERIFY_EMAIL\|ACTIVATE_ACCOUNT, token_hash char(64) unique, expires_at, used_at, created_at)` | client `medlink-admin` (service account: `manage-users`, `view-users`, `query-users`, `query-groups`; secret `${KC_ADMIN_CLIENT_SECRET}`) | `keycloak.admin.*`, `spring.mail.*`, `app.frontend-url`; Mailpit in both compose files (inbox 8025); setup scripts generate the new secret and add missing `.env` keys |
| 2 | — | `medlink-bff`: direct grants on, standard flow off; `ssoSessionIdleTimeout 900`; lockout 5 failures → 900 s; login events stored | `server.servlet.session.timeout=15m`, cookie flags; `oauth2Login` removed; setup scripts log in through endpoint 5 |
| 3 | `user_mfa(user_id PK/FK users, secret_enc, enabled, enrolled_at)` | — | `app.mfa.encryption-key` (env, 32 bytes base64) |
| 4 | — | `passwordHistory(3)` added to the policy | French messages for Keycloak policy errors |
| 5 | `audit_entries(id, timestamp, user_id, action, resource_type, resource_id, ip_address, outcome)` | — | — |

## 6. Sub-sprints

Each is one session and one PR. YouTrack ids in brackets. React tasks (Ahlem) run in parallel
against the contracts above; the React shell (MLink-51) can start at any time.

### Sub-sprint 1 — Registration and email verification
Endpoints 1–4. Backend: service account (MLink-75), `KeycloakAdminService` with `createUser`,
`setPassword`, `deleteUser`, `setEmailVerified`, `getUser` (MLink-76), mail sender + tokens +
endpoints 3–4 (MLink-77), `patients` table (MLink-78), endpoint 2 (MLink-79), CSRF endpoint
(MLink-53), tests (MLink-82). React: signup form (MLink-81), verify-email page + resend (MLink-80).
Exit: a visitor registers, the mail arrives in Mailpit, the link verifies the account; tests
green; setup script green.

### Sub-sprint 2 — Login and session
Endpoints 5 (patient path), 6, 7. Replace `oauth2Login`; roles from the token claims
(reuse `KeycloakRolesMapper` logic); session hardening; `users` row ensured at login for users
created outside the API. Tasks: MLink-55, 53, 54, 58, 66, 67, 68, 83, 84, 86, 65 (login check
in the setup scripts). React: MLink-52 (login page), 56, 85.
Exit: patient logs in and out; unverified → 403; 5 wrong passwords → 423; idle session → 401.

### Sub-sprint 3 — Staff code (TOTP)
Endpoints 8–9 and the staff branch of endpoint 5. Tasks: MLink-62, 63, 65 (staff code check in
the setup scripts). React: MLink-64. Choose and pin a TOTP/QR library at the start (or
implement RFC 6238 with `javax.crypto`); record the choice in `report.md`.
Exit: practitioner enrols with a QR code, logs in with the code; 5 wrong codes → 423.

### Sub-sprint 4 — Passwords
Endpoints 10–11 and the forced-change branch of endpoint 5. Tasks: MLink-87, 88, 90, 91.
React: MLink-89. Exit: weak password refused with a French message; forced change asked at next login.

### Sub-sprint 5 — Accounts and access
Endpoints 12–16; `/api/users` staff-only; role × route test matrix; audit trail.
Tasks: MLink-57, 59, 60, 69, 70, 71, 72, 74. React: MLink-61, 73.
Exit: staff create a practitioner who activates by mail, enrols a code and logs in; the matrix
proves every route; every account action and every refusal is audited.

## 7. Definition of done (every sub-sprint)

- Every contract of the sub-sprint is proven by a test, including the wrong-role and
  wrong-input cases; `./mvnw -B verify` green.
- The setup script ends with 0 failures on a fresh stack (both OS scripts updated when touched).
- No secret, password, token or code in code, logs or tests.
- README / ARCHITECTURE updated when the way to run or the design changed.
- `report.md` updated; PR opened; YouTrack tasks in *To Verify* with the PR link.

## 8. Out of scope for sprint 2

Forgot-password by mail, rate limiting on public endpoints, encryption of patient fields
(MLink-14/24), practitioner-to-patient link (records work), central log store, running more
than one API instance.

## 9. Risks

| Risk | Mitigation |
|---|---|
| Password grant questioned by the jury | Server to server, confidential client, private network, tokens never leave the API; recorded in `report.md` decisions. |
| Keycloak answers differ from what the contract assumes (lockout, "not fully set up") | Each sub-sprint verifies against the real Keycloak first and fixes the contract here, with the reason. |
| Mail delivery in staging/prod | `spring.mail.*` from env; Mailpit only in dev. |
| A session ends before the sub-sprint does | Commit per task, push often, update `report.md` before stopping. |
