# api-MedLink — Dev Workflow Guide (for DevSecOps beginners)

Spring Boot 4.1 / Java 17 API. Package: `com.example.apimedlink`.

Branches: `master` (integration) → `staging` (pre-prod) → `prod` (release).
Flow: feature PR → `master`, promote `master` → `staging`, validate, then
`staging` → `prod` (or tag `v*` for release). CI runs on all three;
DAST runs on `master`/`staging`; CD deploys `staging` → `staging` env and
`prod`/`master`/`v*` → `production` env.
Profiles: `dev` (local) → `staging` (`application-staging.yml`) →
`prod` (`application-prod.yml`). Select via `SPRING_PROFILES_ACTIVE`.

## 1. Setup (once)

```bash
git clone https://github.com/assali-mohamed-ali/api-MedLink.git
cd api-MedLink
cp .env.example .env        # then edit secrets inside, never commit .env
# Java 17 + Docker Desktop required (tests use Testcontainers/Postgres)
./mvnw -B -DskipTests compile
```

Run locally:

```bash
# Option A: Postgres via compose.yaml + app via Maven (dev profile)
docker compose -f compose.yaml up -d
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run

# Option B: full stack (app + postgres + prometheus)
# set JWT_SECRET first: $env:JWT_SECRET="at-least-32-chars-secret-here" (PowerShell)
docker compose -f docker-compose.yml up --build
# Staging / prod: same file, different env + profile
docker compose --env-file .env.staging -f docker-compose.yml up --build   # SPRING_PROFILES_ACTIVE=staging
docker compose --env-file .env.prod -f docker-compose.yml up --build -d    # SPRING_PROFILES_ACTIVE=prod
```

Useful URLs: API `http://localhost:8080`, Swagger `http://localhost:8080/swagger-ui.html`,
health `http://localhost:8080/actuator/health`, Prometheus `http://localhost:9090`.

## 2. Where to code

```text
src/main/java/com/example/apimedlink/
  controller/   REST endpoints (thin, validation only)
  service/      business logic
  repository/   Spring Data JPA interfaces
  model/        JPA entities
  dto/          records for API in/out (never expose password hashes)
  config/       SecurityConfig, JwtConfig, CorsConfig
  security/     JwtAuthFilter, UserDetailsServiceImpl
  exception/    GlobalExceptionHandler
src/main/resources/
  application.properties        base (name only)
  application-dev.yml           local (localhost Postgres, show-sql)
  application-prod.yml          docker/prod (strict, needs JWT_SECRET env)
  db/migration/V1__init.sql     Flyway migrations — see below
src/test/java/.../              mirror packages: controller/, service/, security/, integration/
```

Rules:

- New table/column → new Flyway file `V<N>__description.sql`, never edit an old `V*.sql`.
  Prod uses `ddl-auto: validate`, so the migration IS the schema.
- New endpoint → DTO + service method + controller method + test.
- Passwords: always `PasswordEncoder` (bcrypt, see `UserService`), never log or return them.
- Roles: default `USER`. Don't let callers self-assign `ADMIN` (see `security/threat-model.md` TODO).
- JWT: secret comes from env `JWT_SECRET` (32+ chars). No hardcoded secrets anywhere.

## 3. Before you commit (local gates — same as CI)

```bash
./mvnw -B verify                    # build + all tests + jacoco (needs Docker for Testcontainers)
# If Docker is off, at least:
./mvnw -B clean compile
./mvnw -B -DskipTests test-compile
```

Secrets check (never commit `.env`, tokens, or `JWT_SECRET` values):

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
Secrets (`JWT_SECRET`, DB passwords) live in GitHub Environments
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

- `mvnw.cmd` shows fully modified after pull → line endings. It's normalized
  via `.gitattributes` (`*.cmd eol=crlf`); a `chore: normalize` commit is fine.
- Tests need Docker (Testcontainers). Start Docker Desktop before `./mvnw verify`.
- Spring Boot 4 moved test classes: `AutoConfigureMockMvc` is now in
  `org.springframework.boot.webmvc.test.autoconfigure`, `TestRestTemplate` in
  `org.springframework.boot.resttestclient`. Don't "fix" by downgrading Boot.
- `JWT_SECRET must be set` in compose → export it or fill `.env`.
- Actuator exposes only `health,info,prometheus` — intentional, don't open more
  without a security review.
