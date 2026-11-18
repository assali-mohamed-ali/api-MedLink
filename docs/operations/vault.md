# Vault: the deploy secrets

MLink-168. The staging and production deploys read their secrets from HashiCorp Vault, never from a GitHub secret.
Everything Vault holds for MedLink is configured from `vault/` in this repository.

| Item | Value |
|---|---|
| Host | the `vault` EC2 instance, Ubuntu 26.04; Vault 1.21.0, raft storage, Shamir seal (5 keys, 3 to unseal) |
| Address | `https://13.48.56.245.sslip.io` (Caddy, TLS); Vault itself listens on `127.0.0.1:8200` only |
| Secrets | KV v2 at `medlink/`: `medlink/<staging\|prod>/<api\|front>` |
| GitHub login | JWT auth at `jwt-github/`, GitHub's OIDC issuer, audience `medlink-vault` |
| Audit log | `/var/log/vault/audit.log` (values as HMACs), rotated daily, 30 days |
| Snapshots | daily 02:30 UTC, `/var/backups/vault`, 7 days; the off-host copy is still to set up |

## Who can read what

| GitHub role | Accepted token (claim `sub`) | Policy | Can do |
|---|---|---|---|
| `api-staging` | `repo:assali-mohamed-ali/api-MedLink:environment:staging` | `medlink-staging-api` | read `medlink/staging/api` |
| `api-production` | `repo:assali-mohamed-ali/api-MedLink:environment:production` | `medlink-prod-api` | read `medlink/prod/api` |
| `front-staging` | `repo:assali-mohamed-ali/medlink-dashboard:environment:staging` | `medlink-staging-front` | read `medlink/staging/front` |
| `front-production` | `repo:assali-mohamed-ali/medlink-dashboard:environment:production` | `medlink-prod-front` | read `medlink/prod/front` |

A job gets such a token only when it runs in that GitHub environment, so the environment's protection rules
(which branches or tags may deploy, required reviewers) decide who can reach the secrets. Pull requests, branch runs
without an environment, other repositories and other audiences are refused. Vault tokens last 10 minutes and can
neither write nor list. `vault/test/vault.test.sh` proves each of these on a throwaway Vault.

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
   The non-secret settings (URLs, ports, profiles, the mail account name) are in the repository with the deploy
   (MLink-169), not in Vault.
4. **Revoke the setup token** and delete its file: `VAULT_TOKEN="$(cat medlink-setup.token)" vault token revoke -self && shred -u medlink-setup.token`.

## After a reboot: unseal

Vault starts sealed after every restart of the instance or the service: deploys fail until it is unsealed. Three of
the five key holders each run, on the host:

```sh
VAULT_ADDR=http://127.0.0.1:8200 vault operator unseal      # asks for the key, hidden
```

`vault status` shows `Sealed false` when done. Keep the five keys with five different people or password-manager
vaults; never on the host, in git or in a chat.

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

## Notes

- The setup token can write the MedLink roles, so it could give a role any existing policy: keep its TTL short and
  revoke it after use. The root token is for the repo owner only; generate a new one when needed
  (`vault operator generate-root`) and revoke it after.
- Vault refuses every request when it cannot write its audit log: watch the disk (rotation keeps 30 days).
- Test: `bash vault/test/vault.test.sh` (Docker and openssl; about two minutes). It runs in CI.
