# Vault: the deploy secrets

MLink-168. The API reads its application secrets from HashiCorp Vault. The dashboard reads its SSH deploy bundle
from Vault too, falling back to the GitHub Environment secrets while Vault is unreachable or not seeded.
Everything Vault holds for MedLink is configured from `vault/` in this repository.

| Item | Value |
|---|---|
| Host | the `vault` EC2 instance, Ubuntu 26.04; Vault 1.21.0 **in a container** (`medlink-vault`, `vault/docker`, run from `/opt/vault-docker/compose`), raft storage in `/opt/vault-docker/data`, Shamir seal (5 keys, 3 to unseal) |
| Address | `https://56.228.74.131.sslip.io` (Caddy, TLS); Vault itself listens on `127.0.0.1:8200` only |
| Secrets | KV v2 at `medlink/`: `medlink/<staging\|prod>/<api\|front>` |
| GitHub login | JWT auth at `jwt-github/`, GitHub's OIDC issuer, audience `medlink-vault` |
| Audit log | `/var/log/vault/audit.log` (values as HMACs), rotated daily, 30 days |
| Snapshots | daily 02:30 UTC, `/var/backups/vault`, 7 days; the off-host copy is still to set up |

## Who can read what

| GitHub role | Claim `sub` | Claim `workflow_ref` (workflow file) and `ref` | Policy | Can do |
|---|---|---|---|---|
| `api-staging` | `repo:assali-mohamed-ali/api-MedLink:environment:staging` | `cd.yml` on `refs/heads/staging`, `master` or `main` | `medlink-staging-api` | read `medlink/staging/api` |
| `api-production` | `repo:assali-mohamed-ali/api-MedLink:environment:production` | `cd.yml` on `refs/heads/staging` (automatic deploy from the verified staging run) or `refs/tags/v*` | `medlink-prod-api` | read `medlink/prod/api` |
| `front-staging` | `repo:assali-mohamed-ali/medlink-dashboard:environment:staging` | `container-security.yml` on `staging`, `master` or `main` | `medlink-staging-front` | read `medlink/staging/front` |
| `front-production` | `repo:assali-mohamed-ali/medlink-dashboard:environment:production` | `container-security.yml` on `refs/heads/staging` (from the verified staging run), `refs/heads/prod` or `refs/tags/v*` | `medlink-prod-front` | read `medlink/prod/front` |

Every role also requires `event_name` `push` or `workflow_dispatch`. The environment alone is not enough: any workflow of
the repository that enters an environment gets the same `sub`, so Vault also checks which workflow file asks, from which
ref, and how it was started (PR #130 review). Another workflow, a `pull_request` or `pull_request_target` job, a feature
branch, or production from `master` is refused, even inside the environment. Other repositories and
audiences are refused too. Vault tokens last 10 minutes and can neither write nor list. `vault/test/vault.test.sh` proves
each of these on a throwaway Vault.

GitHub's side still matters: give the `production` environment a deployment rule (`staging` and tags `v*` for the API;
`staging`, `prod` and `v*` for the dashboard) and, if wanted, a required reviewer. Then a run that Vault would accept also needs GitHub's
approval.

After a change to these roles, run `setup.sh` again on the vault host (step 2 of "First set-up", with a fresh setup token).

## First set-up (once)

1. **Repo owner** (holds the root token): load the setup policy and create a short-lived token for whoever runs the
   set-up. Give it as a file, never in a chat:
   ```sh
   vault policy write medlink-setup vault/policies/medlink-setup.hcl
   vault token create -policy=medlink-setup -ttl=8h -orphan -field=token > medlink-setup.token
   ```
   Check first that nothing else already uses `medlink/` or `jwt-github/` (`vault secrets list`, `vault auth list`):
   the scripts touch only those two paths, the token role `medlink-snapshot` and the `medlink-*` policies.
2. **On the vault host**, from a copy of `vault/` (for example `scp -r vault ubuntu@<host>:medlink-vault`):
   ```sh
   export VAULT_ADDR=http://127.0.0.1:8200
   sudo sh medlink-vault/install-host.sh                        # audit log directory, rotation, snapshot job
   VAULT_TOKEN="$(cat medlink-setup.token)" sh medlink-vault/setup.sh
   sudo VAULT_TOKEN="$(cat medlink-setup.token)" sh medlink-vault/install-host.sh   # the snapshot job's token
   ```
3. **Secrets**, generated on the host (nobody sees them). From a terminal (`ssh -t`), so the Gmail app password can be
   typed at the hidden prompt by the person who holds it:
   ```sh
   VAULT_TOKEN="$(cat medlink-setup.token)" sh medlink-vault/seed-env.sh staging
   VAULT_TOKEN="$(cat medlink-setup.token)" sh medlink-vault/seed-env.sh prod
   ```
   The dashboard's SSH bundle comes from the deploy key files, once per environment:
   ```sh
   VAULT_TOKEN="$(cat medlink-setup.token)" sh medlink-vault/seed-front.sh staging ~/.ssh/medlink-front-deploy ~/.ssh/medlink-front-known_hosts
   VAULT_TOKEN="$(cat medlink-setup.token)" sh medlink-vault/seed-front.sh prod ~/.ssh/medlink-front-deploy ~/.ssh/medlink-front-known_hosts
   ```
   Use the same files as `DEPLOY_SSH_KEY`/`DEPLOY_KNOWN_HOSTS`; until the path is seeded the workflow falls back
   to those GitHub Environment secrets. The non-secret settings (URLs, ports, profiles, the mail account name) are in
   the repository with the deploy (MLink-169), not in Vault.
4. **Revoke the setup token** and delete its file: `VAULT_TOKEN="$(cat medlink-setup.token)" vault token revoke -self && shred -u medlink-setup.token`.

## After a reboot: unseal

The container comes back by itself after a reboot, but Vault starts **sealed**: deploys fail until it is unsealed. Three
of the five key holders each run, on the host:

```sh
VAULT_ADDR=http://127.0.0.1:8200 vault operator unseal      # asks for the key, hidden
```

`vault status` shows `Sealed false` when done. Keep the five keys with five different people or password-manager
vaults; never on the host, in git or in a chat.

## The dashboard SSH bundle

`seed-front.sh` base64-encodes two files into `medlink/<env>/front`: `ssh_private_key_b64` (the deploy private key)
and `known_hosts_b64` (the pinned host keys). The `load-deploy-secrets` action decodes them for the deploy, then
deletes the bundle with the runner. Like `seed-env.sh`, it only adds missing keys; it never overwrites one.

When rotating the dashboard's deploy key, update the target's `authorized_keys` and both Vault environments in the
same change, then remove `DEPLOY_SSH_KEY`/`DEPLOY_KNOWN_HOSTS` from the GitHub environments once the Vault path is known
to work.

## Change a secret

`seed-env.sh` never overwrites a value. To change one, with a token allowed to write (a fresh setup token):

```sh
vault kv patch medlink/staging/api MAIL_PASSWORD=-       # reads the value from stdin, not from the command line
```

Then redeploy. Some values are also stored elsewhere and must be changed there in the same step:

| Key | Also change |
|---|---|
| `POSTGRES_PASSWORD`, `APP_DB_PASSWORD`, `KEYCLOAK_DB_PASSWORD` | the role's password in its database (`ALTER ROLE ... PASSWORD`), or the services cannot log in |
| `KC_BFF_CLIENT_SECRET`, `KC_ADMIN_CLIENT_SECRET`, `KEYCLOAK_ADMIN_PASSWORD` | the client secret or admin password in Keycloak: the realm file is imported only once |
| `APP_MFA_ENCRYPTION_KEY` | do not change casually: every enrolled staff code becomes unreadable (`docs/DEPLOYMENT.md`) |
| `APP_REPORT_ENCRYPTION_KEY` | do not change: sealed reports would no longer open |
| `BACKUP_PASSPHRASE` | keep the old one until the last backup made with it has expired (28 days) |

The dashboard's `ssh_private_key_b64` and `known_hosts_b64` are changed the same way, with `vault kv patch
medlink/<env>/front KEY=-`; update the target's `authorized_keys` in the same step.

Every change is in the audit log; KV v2 keeps the previous versions (`vault kv rollback -version=<n>`).

## Snapshots and restore

The daily job writes `/var/backups/vault/vault-<UTC time>.snap` (root 600) and keeps 7 days. A snapshot is encrypted
by Vault: it restores only on a Vault unsealed with the same keys. To restore (needs a root or sudo token):

```sh
VAULT_ADDR=http://127.0.0.1:8200 vault operator raft snapshot restore -force /var/backups/vault/vault-<time>.snap
```

To do: copy each snapshot off the host (an S3 bucket with versioning, from the repo owner), like the database backups.

## Add an environment or an application

Add its policy file in `vault/policies/`, its line in `setup.sh` (the policy list and a `role` call), its paths in
`vault/test/vault.test.sh`, then run the test and `setup.sh` again.

## The Vault installed before the container

The first Vault ran as a system service. It was stopped and disabled on 2026-11-19, and nothing was deleted: its data stays
in `/opt/vault/data`, with an archive in `/var/backups/vault-native-20261119.tgz`. It needs its own unseal keys, held by the
repo owner. Delete both once nobody needs them.

## Notes

- The setup token can write the MedLink roles, so it could give a role any existing policy: keep its TTL short and
  revoke it after use. The root token is for the repo owner only; generate a new one when needed
  (`vault operator generate-root`) and revoke it after.
- Vault refuses every request when it cannot write its audit log: watch the disk (rotation keeps 30 days).
- Test: `bash vault/test/vault.test.sh` (Docker and openssl; about two minutes). It runs in CI.
