# api-MedLink — instructions for AI assistants and new sessions

MedLink is a health portal (patients, practitioners, administrative staff, three clinics).
This repository is its Spring Boot API. It is also the only component that talks to Keycloak.

## Start here, every session

1. Read `docs/sprints/sprint-2/README.md`. It says how a session runs and where to look next.
2. The work to do is in `docs/sprints/sprint-2/plan.md` (design + endpoint contracts) and the
   progress in `docs/sprints/sprint-2/report.md`. Pick the first sub-sprint not marked done.
3. Tasks live in YouTrack, project `MLink` (`https://medlink.youtrack.cloud`), sprint `sprint2`.

## Hard rules

- **The frontend never talks to Keycloak.** Register, verify email, login, code check, logout,
  password changes, account management: browser → this API → Keycloak → this API → browser.
  No Keycloak page, link, mail or token ever reaches the browser.
- **The browser holds only an HttpOnly session cookie** (+ the readable `XSRF-TOKEN` cookie).
  Keycloak tokens stay in the server session. Every write needs the `X-XSRF-TOKEN` header.
- **One id per person, and it is Keycloak's.** Create the Keycloak user first; `users.id` is its
  id (the `sub` claim). Profile tables (`patients`, …) reuse that id as their primary key
  (`@MapsId`). Never generate a user id. Other entities generate a UUID.
- **Roles:** `PATIENT`, `PRACTITIONER`, `ADMINISTRATIVE_STAFF`, given through Keycloak groups.
  Never accept a role or an id from a request body for the caller's own account.
- **Layers:** controller validates and maps (through `mapper/`); services take and return
  entities or plain values, never DTOs; only `KeycloakAdminService` (and the login client)
  call Keycloak.
- **Schema:** Flyway only. Add the next `V<N>__name.sql`; never edit a merged migration.
- **Secrets** only from environment variables. Never log a password, token, code or secret.
- **A task is done when a test proves it.** New endpoint = DTO + mapper + service + controller +
  test for the right role + test for the wrong role.
- **Entities evolve as needed**: change them through a new migration, in the sub-sprint that
  needs the change, and record it in `report.md`.
- Aim: secure, robust, fast, simple to use. Prefer the simplest design that meets the contract.

## Versions (do not downgrade to make code compile)

Spring Boot 4.1 · Java 17 · Keycloak 26.7.5 · PostgreSQL 16. Spring Boot 4 moved test
classes (see README §7). Use only APIs of these versions.

## Commands

- Whole dev stack, checked end to end: `scripts/setup-dev.ps1` (Windows) or
  `scripts/setup-dev.sh` (macOS/Linux). Options: `-SkipTests`, `-Reset`, `-KeepData`, `-Stop`.
- Build and all tests: `./mvnw -B verify` — needs JDK 17+ and Docker running (Testcontainers).
  On the lead's Windows machine the default `JAVA_HOME` is JDK 11: point it at a JDK 17 first.
- Both setup scripts must stay identical in behaviour; `setup-dev.sh` must stay bash 3.2 safe.

## Git

- Pull `master`, check the tree is clean, then branch: `feat/ss<N>-<slug>` for a sub-sprint,
  `fix/…`, `docs/…`, `chore/…` otherwise. Never commit on `master`, `staging` or `prod`.
- One commit per task, message prefixed `feat:`, `fix:`, `docs:`, `chore:`, `refactor:`,
  `test:`; mention the YouTrack id (`MLink-79`) in the body.
- Open a PR to `master`, fill the template honestly, and update `report.md` in the same PR.
