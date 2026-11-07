# Terraform — the API container

This stack creates **one API container** with the Docker provider. It does not
create PostgreSQL or Keycloak: they are shared dependencies that must already be
reachable at `db_url` and `keycloak_internal_url`. If they run on the same Docker
host, attach them to the network named by `network_name` (default `medlink`).

The container gets the same settings as `application-prod.yml`, including both
Keycloak clients, SMTP, the frontend URL and CORS. `JWT_SECRET` is gone: Keycloak
owns identity and the browser only holds the session cookie. The full list is in
`main.tf`; `scripts/ci/check-prod-config.sh` checks it against the production
profile in CI.

## Required settings

| Variable | Secret | Notes |
|---|---|---|
| `app_image` | no | Immutable reference: digest or `sha-<commit>`, never `latest` |
| `db_url`, `db_username`, `db_password` | password yes | JDBC URL reachable from the container |
| `keycloak_public_url` | no | URL the browser reaches |
| `keycloak_internal_url` | no | URL the container reaches (e.g. `http://keycloak:8080`) |
| `keycloak_client_secret` | yes | `medlink-bff` client secret |
| `keycloak_admin_client_secret` | yes | `medlink-admin` service account secret |
| `mfa_encryption_key` | yes | Staff code key, 32 bytes in base64 (`openssl rand -base64 32`); a new key means every staff member enrols again |
| `mail_host`, `mail_port`, `mail_username`, `mail_password`, `mail_from` | username/password yes | Real SMTP, TLS on 587 |
| `app_frontend_url`, `cors_allowed_origins` | no | React origin |

The examples (`terraform.tfvars.*.example`) show every value. Never commit a
real `terraform.tfvars`; it is git-ignored.

## Secrets and state

- Prefer `TF_VAR_<name>` environment variables (or your CI secret store) over
  `-var` on the command line: arguments are visible in process lists and logs.
  All secret variables are marked `sensitive`, so plans redact them.
- Local state (`terraform.tfstate`) is git-ignored and is fine for one admin.
  For staging/prod and CI, copy `backend.tf.example` to the git-ignored
  `backend.tf` (or use `-backend-config`) and use an encrypted remote backend
  with locking. Never commit state.
- The lock file (`.terraform.lock.hcl`) should be reviewed and committed when
  the provider version changes.

## Plan and apply

```bash
cd terraform
terraform init
terraform fmt -check
terraform validate

# Put secrets in the environment, then review the plan:
terraform plan -var-file="terraform.tfvars.prod" -out=tfplan
terraform apply tfplan

curl -fsS http://<host>:8080/readyz
```

## Rollback

Terraform keeps no previous image by itself. To roll back, apply the previous
immutable image reference and wait for the health check:

```bash
terraform apply -var="app_image=ghcr.io/assali-mohamed-ali/api-medlink@sha256:<previous-digest>"
curl -fsS http://<host>:8080/readyz
```

If the container is unhealthy, inspect `docker logs api-medlink`; if the database
or Keycloak is the cause, restore that dependency first. A failed apply leaves
the previous container in place unless Terraform already replaced it.

## CI

`ci.yml` runs `scripts/ci/check-prod-config.sh`, `terraform init -backend=false`,
`terraform fmt -check` and `terraform validate`. The release workflow uses the
compose stack for the actual rollout; this Terraform stack remains the
single-container IaC path for a Docker host with managed dependencies.
