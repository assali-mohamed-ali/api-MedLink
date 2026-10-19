# 0001 — Keep the OAuth password grant for the login, or move to Authorization Code + PKCE

| | |
|---|---|
| Status | **Accepted: option 1**, keep the password grant as a consciously accepted exception |
| Date | proposed 2026-10-18, decided 2026-10-19 |
| Author | Youssef Benchaouacha |
| Deciders | Mohamed Ben Naima (plan owner), Asseli Mohamed Ali (code owner) |
| Tracking | GitHub #50, PR #58, YouTrack MLink-96 (story MLink-15) |

## Decision

**Option 1: the login keeps the OAuth password grant.** Decided on PR #58 by the plan owner
([comment](https://github.com/assali-mohamed-ali/api-MedLink/pull/58#issuecomment-6254098348), 2026-10-18)
and the code owner ([comment](https://github.com/assali-mohamed-ali/api-MedLink/pull/58#issuecomment-6263047895),
2026-10-19: "Continue with option 1").

This is **a consciously accepted exception to the OAuth security best practice, not an equivalent of
Authorization Code + PKCE**: the API still sees passwords, and every second factor and password rule
is ours to build. In the code owner's words, the option sits "in between following standards and
inventing your workflow", so the work now is to make it secure and cover the basics of
authentication and authorisation.

The two conditions of option 1:

1. **Per-IP rate limiting on the public endpoints:** done, PR #60 (GitHub #51, MLink-112): login,
   register and resend answer 429 past their limit, counted in the database and shared by every API
   instance, with the same answer for any email.
2. **This record linked from the sprint report:** done, `docs/sprints/sprint-2/report.md`, Decisions log.

## What option 1 still needs

The basics the code owner asked for, with where each one is tracked:

| What | Why | Where |
|---|---|---|
| Staff code (TOTP) for practitioners and staff | no second factor today | MLink-62/63 (Mohamed), as planned |
| Session cookie `Secure` in staging/prod, `SameSite=Strict` | the cookie is the only credential the browser holds | MLink-84 (Mohamed) |
| Swagger UI, `/v3/api-docs` and `/actuator/prometheus` not public in production | they describe the API and its internals to anyone | GitHub #49 (Youssef) |
| No default database password in staging/prod (`secret` today) | a missing secret must stop the start, not fall back | to track |
| `ADMINISTRATIVE_STAFF` without Keycloak's `manage-users` rights | the `medlink-admin` service account does the admin calls; least privilege | to track |
| Separate database roles for the audit trail | the runtime role can drop the append-only triggers today | GitHub #57 (Mohamed) |

## Context

`POST /api/auth/login` (MLink-55, PR #41) sends the user's email and password to Keycloak's token
endpoint with `grant_type=password` (the *Resource Owner Password Credentials* grant, ROPC), server to
server, as the confidential client `medlink-bff`. The API keeps the tokens in the session; the browser
only gets the session cookie.

We chose it because of a team rule decided on 2026-10-14: **the frontend never talks to Keycloak**
(`CLAUDE.md`, hard rule; `plan.md` §2). ROPC is the only standard way to log in without showing a
Keycloak page.

The problem raised in #50: ROPC is deprecated.

- The OAuth 2.0 Security Best Current Practice (RFC 9700) says the password grant **must not** be used.
- OAuth 2.1 removes it.
- `plan.md` already lists "password grant questioned by the jury" as a risk of the sprint.

## Options

### Option 1 — Keep the password grant, as a documented and accepted risk

What we have today. Already in place (all tested):

- server to server only, confidential client; the browser never sees a token (`LoginFunctionalTest`)
- the password is never logged (`LoginRequest`, `KeycloakTokens`: `toString` hides it)
- a new session id at every login; one generic 401 for a wrong password, an unknown email and a locked
  account (no account enumeration)
- lockout after 5 failures for 15 minutes (MLink-66), Keycloak's event log (MLink-67), our audit trail
  for refusals (MLink-59)

Still missing or limited:

- **no per-IP rate limiting** on the public endpoints (GitHub #51)
- **every second factor is ours to build:** the staff code is planned in the API (MLink-62/63: TOTP
  secret encrypted with AES-GCM, verification, its own lockout); forced password change and password
  history too (MLink-87/88/91). That is security code we write, test and maintain.
- **no passkeys / WebAuthn, ever:** they need a browser ceremony with the identity provider
- the password passes through the API's memory (a vulnerability in the API sees it)

### Option 2 — Authorization Code + PKCE through the BFF

The browser is sent to Keycloak's login page (themed as MedLink), logs in there, and comes back to the
API with a code; the API exchanges it for tokens and keeps them in the session. This is what we had
before #41 (`oauth2Login`), and the BFF model stays: **the browser still never holds a token**.

Gains:

- the standard, recommended flow; the API never sees a password
- **Keycloak does the second factor, natively:** OTP enrolment and check (required action
  `CONFIGURE_TOTP`, a conditional OTP step for staff and practitioners), forced password change
  (`UPDATE_PASSWORD`), password history, and passkeys later
- lockout, brute-force protection and password policy keep working as today, on Keycloak's page
- MLink-62/63 and much of MLink-87/88/91 become realm configuration plus tests instead of our own code

Costs:

- **it reverses the hard rule** "no Keycloak page reaches the browser" (`CLAUDE.md`, `plan.md` §2)
- the login page is Keycloak's, so it needs a MedLink theme (Keycloak themes, or Keycloakify for a
  React-based theme) to look like the app
- React's login page (MLink-52) becomes a redirect; sign-up (sub-sprint 1) and our own mails can stay
- rework: `oauth2Login` and the first-login provisioning come back (they exist in Git history), the
  realm's standard flow goes back on and direct grants off, `LoginController`/`KeycloakLoginClient` go,
  the functional tests' `Browser.login` and the setup scripts' login check go back to the form

## Comparison

| | Option 1: password grant | Option 2: code + PKCE |
|---|---|---|
| Standards (RFC 9700, OAuth 2.1) | against them | follows them |
| API sees the password | yes | no |
| Second factor (staff code) | ours to build (MLink-62/63) | Keycloak, built in |
| Passkeys later | impossible | possible |
| "No Keycloak page" rule | kept | reversed |
| Look of the login page | our React page | a themed Keycloak page |
| Work already done | kept as is | login code replaced (about one sub-sprint of rework) |
| Work still to do in sub-sprints 3–4 | custom TOTP, password change and history | realm configuration, theme, tests |

## Recommendation (author, before the decision; not retained)

**Option 2, if the team accepts a themed Keycloak login page.** It removes the deprecated grant, takes
the second factor and password rules out of our own code, and the cost is lowest **now**: sub-sprint 3
has not started, and the BFF model, sessions, audit and ownership checks do not change.

If the team keeps the rule "no Keycloak page", **option 1** stays acceptable for this project, written
down as an accepted risk, with two conditions: per-IP rate limiting on the public endpoints (#51), and
this record kept in the sprint report so the choice is explained, not hidden.

## Migration plan (if option 2; not retained, kept for reference if the decision is revisited)

1. Team decision recorded here (Status → Accepted), `CLAUDE.md` hard rule and `plan.md` §2 updated.
2. Realm: `medlink-bff` standard flow on, direct grants off; conditional OTP for the staff and
   practitioner groups; `UPDATE_PASSWORD` and password history in the policy.
3. API: bring back `oauth2Login` with first-login provisioning (#29) and token storage for
   `SessionValidityFilter` (#28); remove `POST /api/auth/login`, `LoginController`, `KeycloakLoginClient`;
   keep logout (MLink-54), sessions, ownership and audit.
4. Tests first: the functional tests log in through Keycloak's form again; OTP and forced change are
   tested against the real Keycloak.
5. React (MLink-52): the login button redirects; a MedLink Keycloak theme.
6. Re-map MLink-62/63 and MLink-87/88/91 to realm configuration and tests.

## Consequences

- The login (`POST /api/auth/login`), the hard rule "the frontend never talks to Keycloak" and the
  React login page (MLink-52) stay as they are; no code changes because of this record.
- Sub-sprints 3 and 4 stay as planned: staff code in the API (MLink-62/63), password change, forced
  change and history (MLink-87/88/91).
- The items of "What option 1 still needs" are the security work that comes with this choice.
- Revisit this record if the project leaves its private network, adds passkeys, or the password grant
  disappears from Keycloak.
