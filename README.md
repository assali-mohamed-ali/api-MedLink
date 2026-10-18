# api-MedLink — Dev Workflow Guide (for DevSecOps beginners)

Spring Boot 4.1 / Java 17 API. Package: `com.example.apimedlink`.

> New here? Start with [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): architecture, auth flow,
> DevSecOps pipeline, ISO 27001 mapping and onboarding steps.
>
> Working on the current sprint (or starting an AI session)? Read [CLAUDE.md](CLAUDE.md), then
> [docs/sprints/sprint-2/README.md](docs/sprints/sprint-2/README.md): the endpoint contracts,
> the sub-sprints and the progress report live there.
>
> **Rule since 2026-10-14: the frontend never talks to Keycloak.** Registration, email
> verification, login, staff code, logout and account management all go through this API,
> which talks to Keycloak. The redirect login described below is replaced in sub-sprint 2.

Branches: `master` (integration) → `staging` (pre-prod) → `prod` (release).
Flow: feature PR → `master`, promote `master` → `staging`, validate, then
`staging` → `prod` (or tag `v*` for release). CI runs on all three;
DAST runs on `master`/`staging`; CD deploys `staging` → `staging` env and
`prod`/`master`/`v*` → `production` env.
Profiles: `dev` (local) → `staging` (`application-staging.yml`) →
`prod` (`application-prod.yml`). Select via `SPRING_PROFILES_ACTIVE`.

## 1. Setup (once)

Install Git and Docker Desktop, clone, then run one script. It is the same on every machine and
needs no configuration:

```bash
git clone https://github.com/assali-mohamed-ali/api-MedLink.git
cd api-MedLink
```

```powershell
# Windows
powershell -ExecutionPolicy Bypass -File .\scripts\setup-dev.ps1     # or -SkipTests, -FullStack, -Reset, -KeepData, -Stop
```

```bash
# macOS / Linux
./scripts/setup-dev.sh                                               # or --skip-tests, --full-stack, --reset, --keep-data, --stop
```

The script ([`setup-dev.ps1`](scripts/setup-dev.ps1) / [`setup-dev.sh`](scripts/setup-dev.sh)) creates
`.env` with random secrets, starts Postgres + Keycloak, verifies the realm, runs the tests, starts the
API and logs in as `practitioner.dev` to prove the whole chain works. What it handles for you:

- **JDK:** it finds a JDK 17+ on the machine and uses it for this run only (no `JAVA_HOME` to set).
  With no JDK 17+ it switches to full-stack mode, where Docker builds and runs the API. In that
  mode, if a local PostgreSQL already uses port 5432, the database is published on the next free port.
- **Realm changes:** when `keycloak/realm-medlink.json` changed since your last run (after a `git pull`),
  it deletes the dev volumes and re-imports the realm, so everyone has the same roles, groups and
  users. Dev data is lost on that run; pass `-KeepData` / `--keep-data` to skip it.
  Safety net: before any wipe it saves the realm as it is in your Keycloak to
  `.dev/realm-backups/realm-backup-<date>.json` (flows, roles, groups, clients; no users, secrets
  masked; the 10 latest are kept). Console work you forgot to export is there. It is a backup,
  not sharing: teammates still get a change only when it is in `keycloak/realm-medlink.json`.
- **Line endings and images:** `.gitattributes` and pinned image versions (Postgres 16, Keycloak
  26.7.5) keep the files and containers identical on Windows, macOS and Linux.

| Option (Windows / macOS-Linux) | What it does |
|---|---|
| none | Full run: containers, realm check, `./mvnw verify`, API, login check |
| `-SkipTests` / `--skip-tests` | Same without `./mvnw verify` (fastest way to get a running stack) |
| `-FullStack` / `--full-stack` | Everything in containers, API included, plus Prometheus (no JDK needed) |
| `-Reset` / `--reset` | Delete the dev volumes first: fresh Keycloak and database |
| `-KeepData` / `--keep-data` | Never delete the dev volumes, even when the realm file changed |
| `-Stop` / `--stop` | Stop the API and the containers (volumes kept) |

Day to day: after every `git pull`, re-run the script (with `-SkipTests` / `--skip-tests` when you are
in a hurry). It is safe to re-run, and it is what keeps your Keycloak in step with the team's realm
file. Logs of the last run are in `%TEMP%\medlink-setup` (Windows) or `$TMPDIR/medlink-setup`.

By hand instead (JDK 17 + Docker required, tests use Testcontainers/Postgres):

```bash
cp .env.example .env        # then edit secrets inside, never commit .env
./mvnw -B -DskipTests compile
```

Run locally: two compose files, one job each, one command each.

| Setup | File | Command | Values |
|---|---|---|---|
| **A. Coding** (API on the host, dev profile) | `compose.yaml`: Postgres, Keycloak + its DB, Mailpit | `SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run` | fixed dev-only values in the file, matching `application-dev.yml`; no value from `.env` |
| **B. Full stack** (everything in containers) | `docker-compose.yml`: the same four services + the API image + Prometheus | `docker compose -f docker-compose.yml up --build` | every secret from `.env` (required: it refuses to start without them) |

```bash
# A: Spring Boot's compose integration starts compose.yaml itself (spring.docker.compose.file), then
#    points the app at Postgres. By hand, same file: docker compose -f compose.yaml up -d
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run

# B: fill .env first (KC_BFF_CLIENT_SECRET, KC_ADMIN_CLIENT_SECRET, KEYCLOAK_DB_PASSWORD, KEYCLOAK_ADMIN_PASSWORD, KC_DEV_USERS_PASSWORD)
docker compose -f docker-compose.yml up --build
# Staging / prod: same file, different env + profile
docker compose --env-file .env.staging -f docker-compose.yml up --build   # SPRING_PROFILES_ACTIVE=staging
docker compose --env-file .env.prod -f docker-compose.yml up --build -d    # SPRING_PROFILES_ACTIVE=prod
```

The four shared services are the same in both files: images, commands, container ports, mounts,
volumes, healthchecks and settings. Only the secrets differ. `ComposeFilesTest` fails when they drift,
so change a shared service in both files. The two setups share their volumes (same folder, same
compose project): the setup scripts wipe them when you switch between A and B. Data is kept between
runs, so after checking out a branch with other migrations, re-run with `-Reset` / `--reset` if Flyway
refuses to start.

Useful URLs: API `http://localhost:8080`, Swagger `http://localhost:8080/swagger-ui.html`,
health `http://localhost:8080/actuator/health`, Prometheus `http://localhost:9090`,
Keycloak `http://localhost:8180` (admin console: `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD`, `admin`/`admin` with `compose.yaml`).

### Keycloak (auth)

- Keycloak owns logins, passwords and roles. The backend stores **no password and no role**.
- Realm `medlink` is created from `keycloak/realm-medlink.json` on the first start
  (`--import-realm`). If the realm already exists Keycloak ignores the file (data lives in the
  `keycloak-db-data` volume, separate `keycloak-db` Postgres). In local dev the setup script closes
  that gap: it re-imports the file whenever it changed. So change the realm through the JSON file in a
  PR, not only in your admin console, or your change is lost at the next re-import and nobody else gets it.
  By hand: `docker compose -f compose.yaml down -v` (deletes Keycloak data), then start again.
- Access management: realm roles `ADMINISTRATIVE_STAFF`, `PRACTITIONER`, `PATIENT` (same names as
  the `Role` enum of the class diagram), given through groups `/administrative-staff`,
  `/practitioners`, `/patients` (new users land in `/patients`). To add a role: create the
  role + a group holding it (console or JSON) — no backend change, `KeycloakRolesMapper` turns
  every realm role from the `roles` claim into `ROLE_<NAME>`.
- Client `medlink-bff`: confidential, authorization code + PKCE. The browser only gets a session
  cookie; tokens stay in the backend (BFF). Login starts at `/oauth2/authorization/keycloak`
  until sub-sprint 2 replaces it with `POST /api/auth/login` (see `docs/sprints/sprint-2/plan.md`).
- Test users (dev): `staff.dev`, `practitioner.dev`, `patient.dev`, password = `KC_DEV_USERS_PASSWORD`
  (`MedLink-Dev-2026!` with `compose.yaml`). Delete them in staging/prod after the first start.

## 2. Where to code

```text
src/main/java/com/example/apimedlink/
  controller/   REST endpoints (thin: validation, then entity <-> DTO through a mapper)
  mapper/       entity <-> DTO conversion, called by controllers only
  service/      business logic on entities and plain values (never a DTO)
  repository/   Spring Data JPA interfaces
  model/        JPA entities
  dto/          records for API in/out (never expose password hashes)
  config/       SecurityConfig (oauth2Login BFF), CorsConfig
  security/     KeycloakRolesMapper (Keycloak roles -> ROLE_*)
  exception/    GlobalExceptionHandler
src/main/resources/
  application.properties        base (name + Keycloak client registration)
  application-dev.yml           local (localhost Postgres, show-sql)
  application-prod.yml          docker/prod (strict, needs KEYCLOAK_CLIENT_SECRET env)
  db/migration/V1..V3__*.sql    Flyway migrations — see below
keycloak/realm-medlink.json     Keycloak realm import (roles, groups, client, dev users)
scripts/setup-dev.ps1 / .sh     one-command dev setup (Windows / macOS-Linux): keep both in step
src/test/java/.../              mirror packages: controller/, service/, security/, integration/
```

Rules:

- Names follow the class diagram: roles `PATIENT`, `PRACTITIONER`, `ADMINISTRATIVE_STAFF`.
  The diagram's `passwordHash`, `mfaSecret`, `failedAttempts`, `lockedUntil` and `Session` live in
  Keycloak, not in our tables.
- One id per person, and it comes from Keycloak: `users.id` is the Keycloak user id (the `sub`
  claim). We never generate it: create the Keycloak account first, then save the row with that id.
  Profile tables (`patients`, later `practitioners`) reuse the same id as their own primary key
  (`@MapsId`), so one login can hold several profiles. Every other entity generates its own UUID
  (`@GeneratedValue(strategy = GenerationType.UUID)`); no auto-increment numbers.
- New table/column → new Flyway file `V<N>__description.sql` (next free number: `V4`), never edit
  an old `V*.sql`. Prod uses `ddl-auto: validate`, so the migration IS the schema.
- New endpoint → DTO + mapper + service method + controller method + test. The controller maps;
  the service takes and returns entities or plain values, so it never depends on the API's shape.
- Passwords and roles: Keycloak only. Never store or accept a password in the backend.
- Roles: assign them through Keycloak groups, never from request data.
- Secrets (`KC_BFF_CLIENT_SECRET`, DB/admin passwords) come from env. No hardcoded secrets anywhere.

## 3. Before you commit (local gates — same as CI)

```bash
./mvnw -B verify                    # build + all tests + jacoco (needs JDK 17+ and Docker for Testcontainers)
# If Docker is off, at least:
./mvnw -B clean compile
./mvnw -B -DskipTests test-compile
```

Secrets check (never commit `.env`, tokens, or `KC_BFF_CLIENT_SECRET` values):

```bash
gitleaks detect --source . --config security/.gitleaks.toml --redact -v
```

Optional but appreciated:

```bash
pre-commit run --all-files   # trailing whitespace, yaml, gitleaks, checkov
```

## 4. Commit → push → PR

```bash
git checkout master && git pull
git checkout -b feat/short-description
# ... code + tests ...
git status --short              # review: no .env, no target/, no secrets
git add <files>
git commit -m "feat: what changed"   # or fix:/chore:/docs:
git push -u origin feat/short-description
```

Then open a PR against `master` and fill the template checklist
(`.github/pull_request_template.md`): tests, no secrets, dependency impact,
Docker/Terraform validated if touched, Flyway tested if schema changed.

Branch naming: `feat/...`, `fix/...`, `chore/...`, `security/...`.
Never push directly to `master`/`staging`/`prod`. One PR = one topic. Keep PRs small.

Promote between environments (maintainers):

```bash
git checkout staging && git pull && git merge --no-ff master && git push origin staging
# validate staging (app, ZAP, Prometheus), then:
git checkout prod && git pull && git merge --no-ff staging && git push origin prod
# release tag (triggers prod CD): git tag v1.0.0 && git push origin v1.0.0
```

`staging` uses `.env.staging` + `SPRING_PROFILES_ACTIVE=staging`
(`application-staging.yml`, port 8081, `terraform.tfvars.staging.example`);
`prod` uses `.env.prod` + `SPRING_PROFILES_ACTIVE=prod`
(`application-prod.yml`, port 8080, `terraform.tfvars.prod.example`).
Secrets (`KC_BFF_CLIENT_SECRET`, Keycloak admin, DB passwords) live in GitHub Environments
(`staging`, `production`) — never in git.

## 5. What CI does to your PR (and how to react)

| Check | Workflow | Blocking? | If red |
|---|---|---|---|
| Build + tests | `ci.yml` | YES — test failure fails job, blocks PR | run `./mvnw verify` locally, fix code/tests |
| SAST (CodeQL) | `sast-codeql.yml` | YES — errors fail job, alerts to code scanning | fix flagged pattern, don't suppress without comment |
| Deps CVEs | `sca-dependency-check.yml` | YES — fails on CVSS >= 7 | upgrade dep, or justify suppression in `security/owasp-suppressions.xml` with expiry + ticket |
| Secrets | `secrets-scan.yml` | YES — any leak fails job | remove secret, rotate it, rewrite history if pushed |
| Image + config | `container-scan.yml` (Trivy) | YES — CRITICAL/HIGH fails job | bump base image / fix Dockerfile misconfig |
| DAST (ZAP) | `dast-zap.yml` (master/staging) | YES — `fail_action: true` | fix endpoint, update `security/zap-rules.tsv` only with reason |
| Publish | `cd.yml` (staging→staging env, prod/v*→production env) | env-gated | builds + pushes `ghcr.io/...` image, validates terraform |

Dependabot opens grouped PRs weekly (`dependabot.yml`):
`maven-minor-patch`, `maven-major`, `gha-all`, `docker-all`.
Merge order: minor-patch → actions → major. **Never merge a Java jump
(e.g. `eclipse-temurin-17 → 25/26`)** — `pom.xml java.version=17` must move first,
manually. Docker is pinned to Java 17 by ignore rules.

## 6. Security contacts

Don't open public issues for vulnerabilities. See `SECURITY.md`:
email `security@medlink.example.com` (or DM `@assali-mohamed-ali` with `[SECURITY]`),
include version/commit, repro steps, impact. Threat overview: `security/threat-model.md`.

## 7. Troubleshooting

- `Free these ports first` from the setup script → something else uses 8080 (API) or 8180
  (Keycloak). Stop it, or run the script with `-Stop` / `--stop` if it is an earlier run of ours.
- `release version 17 not supported` / `invalid target release: 17` when you run `./mvnw` yourself →
  your default Java is older than 17. The setup script picks a JDK 17+ by itself; for your own
  commands point `JAVA_HOME` at a JDK 17, or set the project SDK to 17 in your IDE.
- Login works but roles look wrong (`ADMIN`/`DOCTOR` instead of `ADMINISTRATIVE_STAFF`/`PRACTITIONER`),
  or `staff.dev` / `practitioner.dev` cannot log in → your Keycloak still holds an old realm. Re-run
  the setup script (it re-imports a changed realm file), or force it with `-Reset` / `--reset`.
- `permission denied: ./scripts/setup-dev.sh` → `chmod +x scripts/setup-dev.sh` (the executable bit
  is stored in git; this only happens after copying the files by hand).
- A file shows as fully modified after pull → line endings. They are normalized via
  `.gitattributes` (`*.sh` and `mvnw` LF, `*.cmd` and `*.ps1` CRLF); a `chore: normalize` commit is fine.
- Tests need Docker (Testcontainers). Start Docker Desktop before `./mvnw verify`.
- Spring Boot 4 moved test classes: `AutoConfigureMockMvc` is now in
  `org.springframework.boot.webmvc.test.autoconfigure`, `TestRestTemplate` in
  `org.springframework.boot.resttestclient`. Don't "fix" by downgrading Boot.
- `KC_BFF_CLIENT_SECRET must be set` (or another `... must be set`) in compose → `.env` is missing or incomplete. Run the setup script (it creates `.env` with random
  secrets), or fill `.env` from `.env.example` by hand.
- Actuator exposes only `health,info,prometheus` — intentional, don't open more
  without a security review.
