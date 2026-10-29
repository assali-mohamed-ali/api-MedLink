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
| `DEPLOY_SSH_KEY` | secret | Private ed25519 key of the deploy user |
| `DEPLOY_KNOWN_HOSTS` | secret | Pinned host key line(s), preferably hashed |
| `DEPLOY_ENV_FILE` | secret | Full content of `.env.staging` / `.env.prod` (see below) |

Create repository secret `PROMOTION_TOKEN` in **Settings → Secrets and variables → Actions**. Use a fine-grained PAT
restricted to this repository with:
- **Contents:** read/write (merge PRs and create release tags)
- **Pull requests:** read/write (open and merge promotion/release PRs)
- **Issues:** read/write (Release Please labels and release metadata)
- **Checks** and **Commit statuses:** read (wait for PR workflow checks)

The token is used only by workflow automation. Do not put it in an environment secret: the release and promotion
workflows need it before selecting a deployment environment.

Create repository variable `ENABLE_AUTO_PROMOTION` under **Settings → Secrets and variables → Actions → Variables**.
Leave it unset or set it to `false` during the initial migration. Only set it to the string `true` after `staging` and `prod`
have been deliberately reconciled and the workflows, token, environments, and required checks have been verified. The
promotion jobs, tag-based production deployment, and Release Please job are disabled unless this flag is `true`.

For **fully automatic promotion**, do not configure required reviewers on the `production` environment.
The workflow is gated by successful staging deployment/smoke checks, PR checks on the promotion and release PRs,
and a production build/security scan from a lowercase `v*` tag. Add a reviewer gate only if you intentionally want
production to pause for a human approval.

Generate the host-key entry and verify its fingerprint through an independent channel before storing
it:

```bash
ssh-keyscan -H "$DEPLOY_HOST"
ssh-keygen -lf <(ssh-keyscan -H "$DEPLOY_HOST" 2>/dev/null)
```

## Environment file

`DEPLOY_ENV_FILE` is the **content**, not a path. Start from `.env.staging.example` or
`.env.prod.example`, fill every `change-me`, and store it as the environment secret. It must contain
`SPRING_PROFILES_ACTIVE=staging` or `prod`; the workflow rejects a file without it.

Do not set `APP_IMAGE`: the workflow appends the immutable image reference for this run. The values
must match the compose service names (`postgres`, `keycloak`, `keycloak-db`, `mailpit`) and the
Keycloak realm placeholders. Keep the SMTP and Keycloak secrets only in this secret, never in git.

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

### 4. Promote staging to prod

When the staging smoke checks pass, the workflow uses `PROMOTION_TOKEN` to open or update a PR from `staging` to
`prod`. It waits for that PR's checks to finish successfully and merges it as a merge commit. Merge commits preserve
the original Conventional Commit history for version calculation.

### 5. Calculate and publish the release

A push to `prod` runs Release Please. It uses Conventional Commits to calculate the next version and opens a release PR
that updates the Maven version in `pom.xml` and the `CHANGELOG.md`. The workflow waits for the release PR checks, then
merges it automatically. Once the release PR is merged, Release Please creates a lowercase `vX.Y.Z` tag and GitHub
Release. The workflow also opens a checked PR syncing release metadata back from `prod` to `staging`, so the next
staging promotion does not revert the Maven version.

The tag triggers a fresh production build: Maven verification, image scan, SBOM, provenance attestation, Cosign signing,
signature verification, and deployment by immutable digest. The release image is rebuilt from the versioned release commit
rather than reusing the staging digest, because the release PR changes `pom.xml` and the changelog. The application code
must have passed both staging tests and the production tag build/security gate.

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
2. Add the repository secret `PROMOTION_TOKEN` and keep `ENABLE_AUTO_PROMOTION` false until the migration is complete.
3. Prepare the hosts: Docker Engine, Compose v2, deploy user, `DEPLOY_PATH`, reverse proxy, DNS and persistent volumes.
4. Confirm the release-pipeline workflows/configuration are present on `staging` and `prod`, not only on `master`.
5. Configure branch rules for `staging` and `prod`:
   - Require PRs and successful CI/security checks (at minimum `CI / build-test`, `CD / Verify, build and scan exact image`,
     and `PR title / conventional-title` where those checks apply).
   - Permit merge commits, because staging promotions preserve the Conventional Commit history.
   - For full automation, do not require a human approval on the `prod` branch or the `production` environment.
6. Reconcile the existing `master`, `staging`, and `prod` branch histories in reviewed PRs before enabling automatic
   production promotion. They currently diverge; do **not** blindly merge the entire legacy difference into production.
   The first migration must be deliberately reviewed and tested on staging.
7. Once the branches are reconciled, merge a feature PR into `staging`. That starts the deployment, smoke gate, staging-to-prod
   promotion, release PR, version bump, tag, and production deployment sequence.

The workflows use `PROMOTION_TOKEN` instead of `GITHUB_TOKEN` to create PRs that must trigger other workflows automatically.
GitHub suppresses most workflow runs caused by `GITHUB_TOKEN` events; using a PAT is required for this chain. See
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
- Secrets live in GitHub Environments, not in the repository or the compose file.
- Production deployments are tag-only (`v*`); keep branch protections and required CI/security checks enabled even when the reviewer gate is disabled for full automation.
- The GHCR package can stay private; the target logs in per deployment.
- Staging and prod serve Actuator (metrics, `/actuator/health`) only on the management port **9000**. Never publish it
  (no `9000:` under `ports:`, no proxy route, no firewall opening): Prometheus reaches it as `app:9000` on the
  Docker network. The public port keeps only `/livez` and `/readyz`, which answer `{"status":"UP"}` and nothing else
  (GitHub #49). `ManagementPortConfigTest` fails the build if a compose file publishes the port.
