# api-MedLink — instructions for AI assistants and new sessions

MedLink is a health portal (patients, practitioners, administrative staff, three clinics).
This repository is its Spring Boot API. It is also the only component that talks to Keycloak.

## Mandatory AI Contribution Disclosure

Any AI agent working on this repository MUST follow these rules:

1. **Disclose AI usage:** If you generate, modify, refactor, or materially influence any code, tests, configuration, documentation, or other files in a change, you MUST disclose AI assistance in the associated Pull Request.
2. **Update the PR description:** The `Utilisation de l'IA` section offers two exclusive usage choices. Check exactly one: when AI assistance was used, check the following item; when it was not, check `- [x] Cette PR n'utilise pas d'outils d'IA générative.` instead.

   `- [x] J'ai utilisé des outils d'IA générative pour contribuer à cette PR.`

3. **Human review:** When AI was used, include the following item and do not mark it as completed on behalf of the human contributor. When AI was not used, leave it unchecked.

   `- [ ] Je confirme avoir relu, compris et validé les modifications proposées par l'IA.`

4. **No false declarations:** Never claim that tests passed, security checks were completed, or a human reviewed the code unless this has actually been confirmed.
5. **Before finishing:** Verify that the PR description accurately discloses AI assistance. If you cannot edit the PR description directly, explicitly tell the user that they must update it before requesting review.
6. **No exceptions for small changes:** This rule applies to bug fixes, dependency updates, refactoring, tests, documentation, security fixes, and configuration changes.

These requirements are mandatory repository contribution rules, not optional recommendations.

## Start here, every session

1. **Ask whose tickets you work on** (the YouTrack assignee, e.g. `Mohamed.BenNaima`). Sub-sprints
   are split between people: you work only on that person's tickets (see "Team rules").
2. Read the **Status** at the top of `docs/sprints/sprint-2/report.md`: what is merged, which PRs are
   open, who does what, and the next tickets. Then `docs/sprints/sprint-2/README.md` (how a session
   runs) and the section of `plan.md` for your ticket (design + endpoint contracts).
3. Tasks live in YouTrack, project `MLink` (`https://medlink.youtrack.cloud`), sprint `sprint2`.
   Before touching a shared file, look at the teammates' tickets *In Progress*, their comments, and
   their branches and PRs on GitHub.

## Hard rules

- **The frontend never talks to Keycloak.** Register, verify email, login, code check, logout,
  password changes, account management: browser → this API → Keycloak → this API → browser.
  No Keycloak page, link, mail or token ever reaches the browser.
- **The browser holds only an HttpOnly session cookie** (+ the readable `XSRF-TOKEN` cookie).
  Keycloak tokens stay in the server session. Every write needs the `X-XSRF-TOKEN` header.
- **One id per person, and it is Keycloak's.** Create the Keycloak user first; `users.id` is its
  id (the `sub` claim). A person holds **one role only**: `Patient extends User` (JPA `JOINED`,
  `patients.id` = `users.id`, PR #39); a practitioner will be another subclass, never a second
  profile. Never generate a user id. Other entities generate a UUID.
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
- **YouTrack mirrors the work:** a task goes to *In Progress* when work on it starts, *To Verify*
  when its PR opens (with the PR link), *Done* when the PR is merged. One task at a time.
- **Never reveal whether an account exists** on a public endpoint: same status and body either
  way (register 201, resend 202, login one message). Tell the real owner by mail instead.
- Aim: secure, robust, fast, simple to use. Prefer the simplest design that meets the contract.

## Team rules (agreed 2026-10-14)

- **Your tickets only.** Code only tickets assigned to the person you work for. Something on a
  teammate's ticket (a bug, a gap): comment on their ticket, do not fix it. Exceptions: a red
  `master` caused by your code, or a change a teammate asks you for on their ticket.
- **Functional tests first.** Prove behaviour through the real API on a real port, a real Keycloak
  (same image and realm file), a real database and Mailpit, driven over HTTP like a browser:
  `src/test/java/.../functional/` (`FunctionalTestBase`). Commit them red, then make them green, and
  check once that a test fails when the rule it guards is broken. MockMvc or unit tests only for
  branches a functional test cannot reach on demand (an outage, a race).
- **Postman stays in step:** `docs/postman/MedLink-sprint2-ss1.postman_collection.json`, run against
  the dev stack with `npx newman@6.2.1 run <file>` (0 failures) when an endpoint changes.
- **Coordinate on shared files** (`SecurityConfig`, `AuthBffController`, `FunctionalTestBase`, the
  realm file, the setup scripts): say on the teammate's ticket which files and methods you change,
  keep PRs small, and say on their ticket when such a change reaches `master`.

## Versions (do not downgrade to make code compile)

Spring Boot 4.1 · Java 17 · Keycloak 26.7.5 · PostgreSQL 16. Spring Boot 4 moved test
classes (see README §7). Use only APIs of these versions.

## Commands

- Whole dev stack, checked end to end: `scripts/setup-dev.ps1` (Windows) or
  `scripts/setup-dev.sh` (macOS/Linux). Options: `-SkipTests`, `-Reset`, `-KeepData`, `-Stop`.
- Build and all tests: `./mvnw -B verify` — needs JDK 17+ and Docker running (Testcontainers).
  On the lead's Windows machine the default `JAVA_HOME` is JDK 11: point it at a JDK 17 first.
- Both setup scripts must stay identical in behaviour; `setup-dev.sh` must stay bash 3.2 safe.
- **Before every PR**, also run the tests in another class order:
  `./mvnw -B test -Dsurefire.runOrder=reversealphabetical`. CI (Linux) does not run them in the
  Windows order, and classes with the same annotations share one Spring context.

## Cloud sessions (Claude Code on the web)

- A cloud session starts from a fresh clone: nothing from a local session carries over except
  what is written in this repository. Read the Status in `report.md` first.
- The tests need **Docker** (Testcontainers: PostgreSQL, Keycloak, Mailpit). Without it, say so in
  the PR and in `report.md`; CI runs for PRs targeting `master`, `staging`, `prod`, and the configured
  integration branches. CD builds/scans PR candidates but only deploys on a push to `staging` or a lowercase `v*` tag.
- YouTrack and GitHub must be reachable (connectors / `gh`); without YouTrack, list the ticket moves
  for a person to do. No production secret is ever needed: dev values come from `compose.yaml`.

## Git

- Before any work, check the worktree: clean status, in sync with the remote, no unknown
  stash, no local branch ahead of its remote or tracking a gone one
  (`docs/sprints/sprint-2/README.md` step 3). Stop and report if one fails.
- Never commit directly on `master`, `staging`, or `prod`. For product changes, after the release-pipeline migration,
  branch from `staging`: `feat/<slug>`, `fix/<slug>`, `core/<slug>`, `docs/<slug>`, or `chore/<slug>`. Workflow/bootstrap
  changes may first PR to `master`; keep repository variable `ENABLE_AUTO_PROMOTION` false until the branch-baseline migration is reviewed.
- One commit per task, message prefixed `feat:`, `fix:`, `docs:`, `chore:`, `refactor:`,
  `test:`; mention the YouTrack id (`MLink-79`) in the body.
- Product PRs target `staging` after migration; workflow/bootstrap changes may target `master`. Fill the template honestly
  and disclose AI use. Promotion from `staging` to `prod` and release-version PRs are automated but blocked by failing checks.
- Use the intended PR base: product changes → `staging`; workflow/bootstrap changes during migration → `master`.
  Don't stack feature PRs on one another. The promotion PR is specifically `staging` → `prod` and must wait for checks.
- `master` is currently protected (ruleset `mamnou333`): code-owner review (@assali-mohamed-ali), CodeQL, and merge commits.
  Configure PR/check-required rules on `staging` and `prod` during migration. With this PAT-based automation, don't require
  reviewers on those branches or on the production environment: that would block promotion and release-version sync PRs. Keep
  all CI/security checks required. If reviewer gates must stay enforced, use a GitHub App with narrow ruleset bypass for only
  automation PRs. Production deploys only from lowercase `v*` tags.
