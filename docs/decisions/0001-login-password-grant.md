# 0001 — Keep the OAuth password grant for the login, or move to Authorization Code + PKCE

| | |
|---|---|
| Status | **Proposed**, for the team to decide |
| Date | 2026-10-18 |
| Author | Youssef Benchaouacha |
| Deciders | Mohamed Ben Naima (plan owner), Asseli Mohamed Ali (code owner), Ahlem Sakly (React) |
| Tracking | GitHub #50, YouTrack MLink-96 (story MLink-15) |
| Decide before | sub-sprint 3 starts (MLink-62/63, staff code) |

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

## Recommendation (author)

**Option 2, if the team accepts a themed Keycloak login page.** It removes the deprecated grant, takes
the second factor and password rules out of our own code, and the cost is lowest **now**: sub-sprint 3
has not started, and the BFF model, sessions, audit and ownership checks do not change.

If the team keeps the rule "no Keycloak page", **option 1** stays acceptable for this project, written
down as an accepted risk, with two conditions: per-IP rate limiting on the public endpoints (#51), and
this record kept in the sprint report so the choice is explained, not hidden.

## Migration plan (if option 2)

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

Whatever is chosen, this record says why, and `report.md` links to it. Until the team decides, the
password grant stays as it is: nothing in the code changes because of this record.
