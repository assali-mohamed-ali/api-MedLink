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
| `DEPLOY_HEALTH_URL` | variable | Full `/actuator/health` URL reachable **from the GitHub runner** |
| `DEPLOY_SSH_KEY` | secret | Private ed25519 key of the deploy user |
| `DEPLOY_KNOWN_HOSTS` | secret | Pinned host key line(s), preferably hashed |
| `DEPLOY_ENV_FILE` | secret | Full content of `.env.staging` / `.env.prod` (see below) |

`production` must require reviewers. The workflow already refuses to deploy production from `master`;
only `prod`, `v*` tags, or an explicit manual dispatch to `production` can reach that job, and the
environment review remains the final gate.

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

On a push to `staging` (or a manual dispatch to `staging`):

1. `build-push` builds the jar, builds the image, pushes it to GHCR, and writes
   `ghcr.io/<owner>/<repo>@sha256:<digest>` to the job output.
2. `deploy-staging` runs in the `staging` environment:
   - pins the SSH host key and installs the deploy key;
   - creates `DEPLOY_PATH/keycloak` and `DEPLOY_PATH/monitoring/...`;
   - copies `docker-compose.yml`, the realm and the monitoring configuration;
   - writes `.env` (mode `600`) with `DEPLOY_ENV_FILE` and `APP_IMAGE`;
   - logs in to GHCR on the target with the workflow token;
   - runs `docker compose pull app` and `docker compose up -d --no-build --remove-orphans app`;
   - verifies that the running container uses the exact digest;
   - polls `DEPLOY_HEALTH_URL` until the top-level status is `UP`;
   - prints the last 200 API log lines if any step fails.
3. The `production` job does the same in the `production` environment, gated by its reviewers and
   the branch/tag rules.

Pushing to `master` deploys nothing. The promotion path is still `master` → `staging` → `prod`.

## First deployment

1. Create the environment and its variables/secrets (table above).
2. Prepare the host: Docker, Compose v2, deploy user, `DEPLOY_PATH`, reverse proxy and DNS.
3. Put the compose inputs in place: the first workflow run copies them; the Keycloak realm import
   happens on the first start. The database volumes are created automatically.
4. Trigger the workflow:
   - merge to `staging`, or run **CD → Run workflow** with `target=staging`;
   - after validation, merge `staging` into `prod` (or push a `v*` tag) and approve the
     `production` environment.
5. Verify the health URL and the Keycloak login through the public URL.

The Postgres and Keycloak volumes are persistent. Re-running the workflow does not recreate or wipe
them. `docker compose up` only recreates the API container when its image or environment changes.

## Rollback

Every run logs its immutable image reference. To return to the previous revision, edit `APP_IMAGE` on
the host and recreate only the API:

```bash
cd "$DEPLOY_PATH"
sed -i 's|^APP_IMAGE=.*|APP_IMAGE=ghcr.io/<owner>/<repo>@sha256:<previous-digest>|' .env
docker compose --env-file .env -f docker-compose.yml pull app
docker compose --env-file .env -f docker-compose.yml up -d --no-build app
curl -fsS https://<api-host>/actuator/health
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
- The production environment requires reviewers; the branch/tag guard is a second, independent gate.
- The GHCR package can stay private; the target logs in per deployment.
