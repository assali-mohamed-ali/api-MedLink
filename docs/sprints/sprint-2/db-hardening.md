# Database hardening: GitHub #64, #57 and #67 (Mohamed)

Plan written 2026-11-09, to be picked up by a new session. Read this file first, then the **Progress** list at
the bottom: it says what is done, the last commit and the next step.

GitHub #68 (MFA for the Keycloak bootstrap admin) is **not** part of this plan.

## Scope and branches

These are security and operations fixes outside any epic, so they get their own branch (CLAUDE.md "Epic branches"):

| Issue | What | Branch | PR |
|---|---|---|---|
| #64 (MLink-164) | Production and staging refuse to start without real DB credentials | `fix/db-hardening` | one PR for #64 + #57 |
| #57 (MLink-165) | Two DB roles: Flyway owns the schema, the app can only use it (audit trail protected) | `fix/db-hardening` | same PR |
| #67 (MLink-166) | Backup and restore of both PostgreSQL databases, with a tested drill | `chore/db-backup-restore` (from `master`) | its own PR |

Order: #64 first (small, same files), then #57 on the same branch, then #67. #64 and #57 touch the same files
(`docker-compose.yml`, `application-*.yml`, `.env`, setup scripts), which is why they share one branch.

Same loop as always: pull, red test commit, green commit, check green (`./mvnw -B verify` + reversed order),
pull again, push. Each task: a red commit then its green commit, `#64`/`#57`/`#67` and the YouTrack id in the body.

## Facts checked on `master` (2026-11-09, `2841f7c`)

- Fallback credentials `myuser` / `secret`:
  - `src/main/resources/application-prod.yml` lines 4–5 and `application-staging.yml` lines 4–5:
    `${SPRING_DATASOURCE_USERNAME:myuser}`, `${SPRING_DATASOURCE_PASSWORD:secret}`;
  - `docker-compose.yml` lines 5–7, 17, 30–32: `${POSTGRES_USER:-myuser}`, `${POSTGRES_PASSWORD:-secret}`;
  - `application-dev.yml` lines 4–5 has the same defaults: **allowed**, dev only;
  - `compose.yaml` (dev, used with `mvnw spring-boot:run`) has fixed `myuser` / `secret`: **allowed**, dev only.
- `docker-compose.yml` is also used by the setup scripts' `--full-stack` mode (`setup-dev.sh` l.24, l.125), and
  the scripts write `POSTGRES_PASSWORD` into `.env` (l.284). So removing the compose defaults must keep `.env`
  complete, or `--full-stack` breaks.
- Terraform passes `db_url`, `db_username`, `db_password` (`terraform/main.tf` l.44–46); the tfvars examples hold
  `myuser` / `change-me` / `change-me-prod`.
- Tests: `TestcontainersConfiguration` starts `postgres:16` with `@ServiceConnection` (one user for everything).
- One DB user today: Flyway and the app both connect as `POSTGRES_USER`, which owns every table. `audit_entries`
  (V7) refuses UPDATE/DELETE/TRUNCATE with triggers, but the owner can drop or disable those triggers.
- Migrations on `master` end at `V14__slot_search_index.sql`. `epic/MLink-9` (the lead's branch) holds
  `V13__patient_documents.sql`, which already clashes with master's V13. **Before numbering, check master and open
  PRs again** (memory "migration numbering"), take the next free number (V15 today) and say so on MLink-9.

## Part A: #64, fail closed on missing DB credentials

Design (simplest that meets the issue):
- `application-prod.yml` and `application-staging.yml`: `${SPRING_DATASOURCE_USERNAME}` and
  `${SPRING_DATASOURCE_PASSWORD}` with **no default**.
  **Found in A1:** that alone does *not* stop the start. `spring.datasource.*` is bound as configuration
  properties, and Spring Boot's binder keeps an unresolved `${...}` as plain text: the app tries to log in as the
  literal user `${SPRING_DATASOURCE_USERNAME}` and fails later, on the connection, with a confusing message. The
  check below is what makes it fail closed.
- A small startup check, prod and staging profiles only, that refuses a missing value (absent, empty, or still an
  unresolved `${...}`) and the known placeholders (`myuser`, `secret`, `change-me`, `change-me-prod`): the app
  stops **before anything connects to the database** (so it must run before Flyway: a `BeanFactoryPostProcessor`
  or an environment check, not an ordinary bean), and the message names the variable, never its value.
  Read the values through `Environment.getProperty`, which fails on an unresolved placeholder instead of keeping it.
- The staging/prod integration tests that start with `@ServiceConnection` (`SecureCookies`, `ManagementPort`,
  `ApiDocsExposure`, `ReadinessProbe` integration tests) do not set these variables: A2 gives them dummy
  non-placeholder values (like they already do for the Keycloak secrets), or the check must accept them another way.
- `docker-compose.yml`: `${POSTGRES_USER:?POSTGRES_USER is required}` (same for the password and the DB name):
  `docker compose` refuses to start when one is missing or empty.
- Dev keeps its fixed values (`compose.yaml`, `application-dev.yml`).

Tasks:
- [x] **A1** Red: `DatabaseCredentialsStartupTest` starts the real application (`SpringApplicationBuilder`, real
  profile file) under prod and staging with a fake process environment (the real one removed) and the database URL
  on a closed port, no Docker. Missing username, missing password and each placeholder must fail with a root cause
  that names the variable and not the value; a control case (real-looking values) must *not* be refused. Red run:
  18 cases fail with `Connection refused` (the start got past the missing check), the 2 control cases pass. ~35 s.
- [x] **A2** Green: no default in `application-prod.yml` / `application-staging.yml`;
  `config/DatabaseCredentialsCheck` (`@Profile({"staging","prod"})`, a `BeanFactoryPostProcessor`, so it runs
  before Flyway) refuses a missing, empty, unresolved or placeholder value. The 4 staging/prod integration tests
  set `TestcontainersConfiguration.UNUSED_DB_CREDENTIAL` (never used to connect: `@ServiceConnection` wins).
  Mutation check done once: `${SPRING_DATASOURCE_USERNAME:medlink}` back in prod turned the prod "no username"
  case red.
- [ ] **A3** Red then green: an offline test script for the compose file (like `scripts/check-lockout.test.sh`):
  `docker compose -f docker-compose.yml config` with the variables unset fails and names them, and with values
  set passes. Then the `:?` changes.
  Red done: `scripts/compose-credentials.test.sh` (+ a step in `ci.yml`). It passes `--env-file` an empty file,
  or compose would read the developer's `.env` and pass by accident; every other `${X:?}` variable gets a dummy.
  Red run: 6 of 7 cases fail (missing/empty accepted), the "all set" case passes.
  Note for A4/A5: the full stack runs `SPRING_PROFILES_ACTIVE=dev` by default (`.env.example`), so A2's check
  does not apply there; someone switching the full stack to `prod` must also replace `myuser` (say so in
  `.env.example`).
- [ ] **A4** Setup scripts (both, same behaviour, `.sh` bash 3.2 safe): `--full-stack` still works, since `.env`
  already holds the values. Run both scripts once: 0 failures.
- [ ] **A5** Docs: `.env.example`, `terraform/terraform.tfvars*.example` (say the placeholders are refused),
  `report.md`, the threat model line. Comment on #64 with the commits.

## Part B: #57, two database roles

Design:
- **Owner role** = `POSTGRES_USER` (it already exists and owns the tables): used **only by Flyway** through
  `spring.flyway.user` / `spring.flyway.password`.
- **Runtime role** `medlink_app` (name in a Flyway placeholder `${appRole}`), password from a new env key
  `APP_DB_PASSWORD`: used by the app through `spring.datasource.*`. No ownership, no `ALTER`, no `TRIGGER`.
- The role is created by **one init script** `docker/postgres/initdb/10-app-role.sh` (reads `APP_DB_PASSWORD`,
  creates the role only if missing). The same file is used by `compose.yaml`, `docker-compose.yml` and the
  Testcontainers container (DRY: one place defines the role).
- Grants in the next migration (`V15__app_role_grants.sql` if still free): `SELECT, INSERT, UPDATE, DELETE` on the
  app's tables, **only `SELECT, INSERT` on `audit_entries`**, `USAGE` on sequences, and `ALTER DEFAULT PRIVILEGES`
  so tables made by later migrations are granted too. Revoke `TRIGGER`/`TRUNCATE` from the runtime role.

To check first (spike, 30 min, no commit): how Spring Boot 4.1 `@ServiceConnection` behaves when
`spring.flyway.user` is set. Flyway must connect as the container's owner and the datasource as `medlink_app`.
If `@ServiceConnection` forces one user on both, set the datasource with a `@DynamicPropertySource` instead.

Tasks:
- [ ] **B1** Red: a functional test connected as the runtime role: `UPDATE audit_entries`, `DROP TRIGGER`,
  `ALTER TABLE audit_entries` and `TRUNCATE` all fail; `INSERT` and `SELECT` work.
- [ ] **B2** Green: the init script, the Testcontainers container using it, the migration with the grants,
  `spring.flyway.user/password` + `spring.datasource.*` per profile. The whole suite runs as the runtime role:
  `./mvnw -B verify` + reversed order green.
- [ ] **B3** Dev stack: both compose files mount the init script; the setup scripts generate `APP_DB_PASSWORD`
  (like `new_secret`, added to the "keys added later" loop and the empty/change-me check). An existing dev volume
  has no `medlink_app` yet: the setup script creates it if missing (same SQL as the init script, idempotent), so
  `-KeepData` keeps working. Both scripts, 0 failures.
- [ ] **B4** Prod/staging config: `SPRING_FLYWAY_USER/PASSWORD` + runtime datasource in `docker-compose.yml`,
  Terraform variables (`db_flyway_username`, `db_flyway_password`) and tfvars examples, `.env.example`.
- [ ] **B5** Docs: `report.md` (MLink-59 "Limits" entry closed), `docs/ARCHITECTURE.md`, threat model, README
  dev-stack section (`-Reset` not needed thanks to B3). Comment on #57. Postman: no endpoint changes, nothing to do.
- [ ] **B6** PR `fix/db-hardening` → `master` (AI section honest, review box left for Mohamed). Before it:
  pull, verify + reversed order, both setup scripts.

## Part C: #67, backup and restore (branch `chore/db-backup-restore`)

Design (keep it small and repeatable):
- `scripts/db-backup.sh`: `pg_dump -Fc` of the app DB and of the Keycloak DB from their containers, taken one
  after the other, each piped straight into `openssl enc -aes-256-cbc -pbkdf2` with the passphrase from
  `BACKUP_PASSPHRASE` (env only, never an argument, never logged). Output to a directory given as an argument,
  outside the repo and the Docker volumes; `*.dump.enc` in `.gitignore`. File names carry the UTC time.
- `scripts/db-restore.sh`: decrypt and `pg_restore --clean --if-exists` into a given database.
- Servers are Linux: bash only (the "both setup scripts" rule is for `setup-dev.*`, not these).

Tasks:
- [ ] **C1** Red: an offline test (fake `docker`, like `check-lockout.test.sh`) checks that the scripts refuse to
  run without `BACKUP_PASSPHRASE`, never print it, and never write inside the repo.
- [ ] **C2** Green: the two scripts.
- [ ] **C3** Restore drill on the dev stack: write a marker row; back up both DBs; `setup-dev -Reset`; restore;
  check the marker row and that `patient.dev` can still log in (Keycloak DB restored); write down the measured time.
  **Needs ~2.5 GB of free RAM** (close Ollama and Chrome tabs first; stop the stack with `-Stop` after).
- [ ] **C4** Runbook `docs/operations/backup-restore.md`: frequency (daily), retention (7 daily, 4 weekly),
  RPO 24 h, RTO = the measured drill time, where backups live (off the host), who can read them, the passphrase in
  the secret store, consistency (both DBs in the same run), verification steps. Link from `docs/ARCHITECTURE.md`
  (backup line goes from "Gap" to "Done").
- [ ] **C5** PR `chore/db-backup-restore` → `master`. Comment on #67.

## Bookkeeping

- YouTrack: MLink-164 (#64), MLink-165 (#57), MLink-166 (#67), assigned to Mohamed, under epic MLink-5 (socle technique et sécurité). In Progress
  when its part starts, To Verify when pushed green, Done when its PR merges.
- GitHub: comment on each issue when its part is pushed.

## Progress

- [x] Plan written (2026-11-09), issues #57, #64, #67 taken by Mohamed.
- [x] A1 red test committed (2026-11-09), not pushed: a red commit is pushed only with its green commit.
- [x] A2 green committed (2026-11-09), not pushed yet (push waits for Mohamed's go). MLink-164 is In Progress.
- Next step: **A3** (offline test script for `docker-compose.yml`, then the `:?` changes).
- Last commit: the A2 green commit.
