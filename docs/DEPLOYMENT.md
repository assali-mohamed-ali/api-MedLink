# Deployment (staging and production)

CD deploys the **same image** that CI built to a Docker host over SSH. The host runs the
`docker-compose.yml` stack (API, PostgreSQL, Keycloak, Keycloak database, Prometheus); GitHub Actions
only builds the image, records its immutable digest, copies the compose inputs, and asks the host to
pull that digest. The host never builds the application.

This is deliberate: Terraform local state on an ephemeral runner cannot safely own a production
container. Terraform remains available for a single-container deployment (`terraform/`), while CD
owns the complete compose stack.

## Target host

- Linux host with Docker Engine and Docker Compose v2 (`docker compose version`).
- SSH access for a dedicated deploy user in the `docker` group.
- A reverse proxy (HTTPS) in front of the API and Keycloak.
- Persistent storage for the Postgres and Keycloak volumes.
- Outbound access to `ghcr.io` and the SMTP server.
- `bash`, `curl` and `ssh` on the host (the rollout uses `bash -s`; the health check runs on the
  runner).

## GitHub Environment configuration

Create the `staging` and `production` environments in **Settings → Environments**. Put the values
below in each environment, so the same workflow reads different hosts and secrets:

| Name | Kind | Purpose |
| --- | --- | --- |
| `DEPLOY_HOST` | variable | SSH host or IP of the target |
| `DEPLOY_USER` | variable | SSH user allowed to run Docker Compose |
| `DEPLOY_PATH` | variable | Absolute directory for `docker-compose.yml`, `.env`, `keycloak/`, `monitoring/` |
| `DEPLOY_HEALTH_URL` | variable | Full `/readyz` URL on the API's public port, reachable **from the GitHub runner** (e.g. `https://<api-host>/readyz`); `/actuator/health` is on the private management port in staging and prod |
| `DEPLOY_SSH_KEY` | secret | Private ed25519 key of the deploy user (the dashboard falls back to it only until its Vault path is seeded) |
| `DEPLOY_KNOWN_HOSTS` | secret | Pinned host key line(s), preferably hashed (same fallback) |
| `VAULT_ADDR` | variable | Dashboard repository only: optional Vault address; defaults to `https://56.228.74.131.sslip.io` |

The API's non-secret settings are in `deploy/env/staging.env` and `deploy/env/production.env`; the API gets its
application secrets from Vault (`medlink/<env>/api`). The dashboard's SSH bundle comes from Vault
(`medlink/<env>/front`) with the environment secrets above as fallback. There is no `DEPLOY_ENV_FILE` any more.

Optional repository variables `STAGING_PUBLIC_URL` and `PRODUCTION_PUBLIC_URL` override the smoke-test base URLs
(defaults: `https://13.51.30.39.sslip.io` and `https://56.228.94.12.sslip.io`).

Create repository secret `CROSS_REPO_TOKEN` in **Settings → Secrets and variables → Actions**. A fine-grained PAT with
**Actions: read** on `assali-mohamed-ali/api-MedLink` and `assali-mohamed-ali/medlink-dashboard` lets each gate read the
other repository's workflow jobs. The repositories are public, so the gate also works without it, but GitHub's
unauthenticated rate limit is much lower.

Create repository variable `AUTO_DEPLOY_PROD` under **Settings → Secrets and variables → Actions → Variables**.
Leave it unset to deploy the verified staging image to production automatically after both repositories' staging
deployments and smoke tests pass; set it to `false` to pause automatic production deployment.

`ENABLE_AUTO_PROMOTION` and `PROMOTION_TOKEN` are the legacy release path. They are only needed when
`AUTO_DEPLOY_PROD=false` and you want the old `staging -> prod` PR, Release Please and tag-based deployment chain.
The legacy PR path also needs a fine-grained PAT with **Contents**, **Pull requests**, **Issues**, **Checks** and
**Commit statuses** access.

For **fully automatic promotion**, do not configure required reviewers on the `production` environment. The
auto-prod job waits for the API and dashboard staging deploy/smoke jobs, verifies the image signature and deploys
the same digest. Add a reviewer gate only if you intentionally want production to pause for a human approval.

Generate the host-key entry and verify its fingerprint through an independent channel before storing
it:

```bash
ssh-keyscan -H "$DEPLOY_HOST"
ssh-keygen -lf <(ssh-keyscan -H "$DEPLOY_HOST" 2>/dev/null)
```

## Environment settings and secrets

The non-secret settings live in `deploy/env/staging.env` and `deploy/env/production.env`. The workflow renders them
with the Vault secrets and copies the result to the host as `.env`; do not set `APP_IMAGE` in those files, because the
workflow appends the immutable digest. `SPRING_PROFILES_ACTIVE` must be `staging` or `prod`.

The API reads `medlink/<env>/api` from Vault with GitHub OIDC (`api-staging`/`api-production`), then deletes its
temporary token. The dashboard reads `medlink/<env>/front` (`front-staging`/`front-production`). See
`docs/operations/vault.md` for seeding and rotation. Never put a secret in `deploy/env/`, git or a compose file.

Database (GitHub #64, #57): `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` (the schema owner, used by Flyway
only) and `APP_DB_PASSWORD` (the API's runtime role `medlink_app`) are required: `docker compose` stops without one.
`SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` need no line: `docker-compose.yml` takes them from `POSTGRES_USER` /
`POSTGRES_PASSWORD`. The deploy action copies `docker/postgres/initdb/10-app-role.sh` to the server and runs it on
every deploy, after the database is up and before the API starts, so a volume made before #57 gets the role's login
too (the script is safe to run again). A new volume also gets it from the image's init.

`APP_MFA_ENCRYPTION_KEY` seals the practitioners' and staff members' code secrets (MLink-62). Generate it once
per environment with `openssl rand -base64 32` (32 bytes, base64) and keep it with the other secrets: the API
does not start without a valid key. Never share it between staging and prod, and do not change it casually: a
new key makes every enrolled code unreadable, so each practitioner and staff member would enrol again.

## What the workflow does

### 1. Pull request into staging

CI and CD validate the PR. The workflows run Maven verification/tests and scan the exact runtime image for HIGH/CRITICAL
vulnerabilities, secrets and misconfiguration. PR events do not publish or deploy anything.

### 2. Merge into staging

On a push to `staging`, CD verifies and tests the commit, builds the runtime image, runs Trivy, generates a CycloneDX
SBOM, and publishes the scanned image to GHCR by commit SHA. It records the immutable digest, attests the build and SBOM,
and signs the digest with Cosign. CD then verifies that signature and deploys the image to the `staging` environment
using Docker Compose over SSH.

### 3. Test the deployed staging service

After the rollout health check, a separate runner checks:
- `/readyz` and `/livez` return HTTP 200 with `{"status":"UP"}`;
- `/api/auth/csrf` returns HTTP 204 and sets the `XSRF-TOKEN` cookie.

Any failure blocks promotion. The checks run from GitHub-hosted runners, so `DEPLOY_HEALTH_URL` must be reachable from
the public internet or an equivalent runner network.

### 4. Deploy production from the verified staging image

After each repository's staging smoke test, its gate waits for the other repository's `deploy-staging` and
`smoke-staging` jobs at the current staging HEAD. The gate also requires `staging` to contain `master`; a stale staging
branch cannot deploy to production. Both gates check the individual jobs, not the other workflow's overall conclusion,
so the two waits cannot deadlock.

When both repositories are green, each auto-prod job verifies the Cosign signature and deploys the **same staging
digest** to the `production` environment, then runs the production smoke test. `AUTO_DEPLOY_PROD=false` pauses this
step without changing the staging deployment.

### 5. Legacy release path

With `AUTO_DEPLOY_PROD=false` and `ENABLE_AUTO_PROMOTION=true`, the old path remains available: `PROMOTION_TOKEN` opens
or updates a `staging -> prod` PR, waits for its checks and merges it. Release Please then calculates the version from
Conventional Commits, opens and merges its release PR, and creates a lowercase `vX.Y.Z` tag. The tag triggers a fresh
production build (verify, scan, SBOM, attest, sign) followed by tag-based deployment. This path is separate from the
automatic staging-digest deployment.

### Version rules

| Conventional Commit type | Version change |
|---|---|
| `fix:` | Patch, e.g. `v1.0.1 -> v1.0.2` |
| `feat:` | Minor, e.g. `v1.0.1 -> v1.1.0` |
| `feat!:`, `fix!:`, or a `BREAKING CHANGE:` footer | Major, e.g. `v1.0.1 -> v2.0.0` |
| `docs:`, `test:`, `chore:`, `ci:` | No release by themselves |

The branch name does not determine the version. Use Conventional Commit titles/messages on feature PRs; a `core/*`
branch can contain a feature, a fix, or a refactor, so it is not automatically a major version.

## First deployment and one-time migration

1. Create the `staging` and `production` environments and configure all variables/secrets in the table above.
2. Seed both Vault paths (`seed-env.sh` and `seed-front.sh`) or keep the GitHub Environment fallback secrets;
   set `AUTO_DEPLOY_PROD=false` until the first staging deployment has been reviewed.
3. Prepare the hosts: Docker Engine, Compose v2, deploy user, `DEPLOY_PATH`, reverse proxy, DNS and persistent volumes.
4. Confirm the workflows/configuration are present on `staging` and `prod`, not only on `master`.
5. Configure branch rules for `staging` and `prod`:
   - Require PRs and successful CI/security checks (at minimum `CI / build-test`, `CD / Verify, build and scan exact image`,
     and `PR title / conventional-title` where those checks apply).
   - Permit merge commits, because staging promotions preserve the Conventional Commit history.
   - Keep required checks enabled. For automatic production deployment, do not require reviewers on the `production`
     environment; add a reviewer gate only if production must pause for a human.
6. Reconcile the existing `master`, `staging`, and `prod` branch histories in reviewed PRs before relying on
   automatic production deployment. They currently diverge; do **not** blindly merge the entire legacy difference into
   production. The first migration must be deliberately reviewed and tested on staging.
7. Merge a feature PR into `staging` in both repositories. Each workflow deploys and smoke-tests staging, its gate waits
   for the other repository, and (with `AUTO_DEPLOY_PROD` unset) each deploys the verified digest to production.

On the legacy path, the workflows use `PROMOTION_TOKEN` instead of `GITHUB_TOKEN` to create PRs that must trigger
other workflows automatically. GitHub suppresses most workflow runs caused by `GITHUB_TOKEN` events; using a PAT is
required for that chain. See
[GitHub's workflow-triggering guidance](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow).

## Rollback

Every run logs its immutable image reference. To return to the previous revision, edit `APP_IMAGE` on
the host and recreate only the API:

```bash
cd "$DEPLOY_PATH"
sed -i 's|^APP_IMAGE=.*|APP_IMAGE=ghcr.io/<owner>/<repo>@sha256:<previous-digest>|' .env
docker compose --env-file .env -f docker-compose.yml pull app
docker compose --env-file .env -f docker-compose.yml up -d --no-build app
curl -fsS https://<api-host>/readyz
```

Do not edit the workflow retry to fake a digest. A re-run builds the same commit again; the digest is
usually identical but is not a rollback mechanism.

Flyway migrations are forward-only. Rolling the image back after a schema migration can be
incompatible: roll forward with a fix, or restore the database backup taken before the release. The
same rule applies to Keycloak: keep the `keycloak-db-data` volume and its backup.

## Failure and recovery

| Failure | Effect | Recovery |
| --- | --- | --- |
| Build/push fails | Nothing reached the host | Fix the build, re-run |
| SSH or host-key check fails | Nothing changed | Fix `DEPLOY_SSH_KEY` / `DEPLOY_KNOWN_HOSTS` |
| `pull` fails | Old container still runs | Check GHCR visibility and the target's login |
| `up` starts a bad image | New container may be unhealthy | Collect logs, restore the previous digest, `up` again |
| Health check times out | New container is left in place; old one is gone | Roll back with the previous digest and check the DB/SMTP/Keycloak connectivity |
| Host unreachable | Deployment job fails; the old stack keeps running | Restore SSH access, re-run |

The deploy job prints the API logs on failure. Container logs are also available with:

```bash
cd "$DEPLOY_PATH"
docker compose --env-file .env -f docker-compose.yml logs --tail=200 app
docker compose --env-file .env -f docker-compose.yml ps
```

## Security notes

- Host keys are pinned (`StrictHostKeyChecking=yes`); password authentication and
  `StrictHostKeyChecking=no` are not used.
- The deploy key is dedicated, mode `600`, and only the runner writes it for the job.
- The environment file is copied with mode `600`; the GHCR token is piped to `docker login --password-stdin`
  and is never printed.
- Application secrets live in Vault; the dashboard's SSH bundle uses GitHub Environment secrets only as a fallback.
  No secret is in the repository or the compose file.
- Automatic production deployment uses the verified staging digest and requires both repositories' staging gates.
  Keep branch protections and required CI/security checks enabled even when the reviewer gate is disabled.
- The GHCR package can stay private; the target logs in per deployment.
- Staging and prod serve Actuator (metrics, `/actuator/health`) only on the management port **9000**. Never publish it
  (no `9000:` under `ports:`, no proxy route, no firewall opening): Prometheus reaches it as `app:9000` on the
  Docker network. The public port keeps only `/livez` and `/readyz`, which answer `{"status":"UP"}` and nothing else
  (GitHub #49). `ManagementPortConfigTest` fails the build if a compose file publishes the port.
