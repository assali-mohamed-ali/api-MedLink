# Sprint 2 report

Updated by every sub-sprint PR. Newest information first inside each section.

## Status

| Sub-sprint | Scope | State | PR | Date |
|---|---|---|---|---|
| 0 | Sprint plan, backend-only design, YouTrack re-mapped | Done | docs/sprint-2-plan | 2026-10-14 |
| 1 | Registration and email verification (endpoints 1–4) | **Next** | — | — |
| 2 | Login and session (endpoints 5–7) | To do | — | — |
| 3 | Staff code, TOTP (endpoints 8–9) | To do | — | — |
| 4 | Passwords (endpoints 10–11) | To do | — | — |
| 5 | Accounts and access (endpoints 12–16) | To do | — | — |

**Next step:** start sub-sprint 1 in a new session: read `README.md` in this folder, then
section 6 "Sub-sprint 1" of `plan.md`.

## Where the code stands (start of sprint 2)

- Merged on `master` (PR #22): roles `PATIENT` / `PRACTITIONER` / `ADMINISTRATIVE_STAFF`;
  `users.id` = Keycloak id (V3); controller maps through `mapper/UserMapper`, service returns
  entities; one-command setup scripts for Windows and macOS/Linux with automatic realm
  re-import and realm backup; Postgres pinned to 16.
- Still from the earlier design, replaced in sub-sprint 2: login by redirect to Keycloak
  (`oauth2Login` in `config/SecurityConfig`), dev users' rows created nowhere yet.
- Not tested end to end yet: the realm backup before a wipe (written while Docker was off on
  2026-10-13). First real run: the next time the setup script re-imports the realm.
- On the lead's machine the local database applied an earlier V3: run the setup script with
  `-Reset` once.

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
