# The MedLink hosts: preparation and access

MLink-171. Four EC2 instances in eu-north-1, Ubuntu 26.04, created by the repo owner. Only he has the AWS console.

| Host | Role | Public IP (2026-11-18) | Private IP | Edge |
|---|---|---|---|---|
| staging-server | `staging`: API, Keycloak, both databases and the front | 13.51.70.18 | 172.31.36.231 | Caddy `https://13.51.70.18.sslip.io`: `/api/*`, `/livez`, `/readyz` → 127.0.0.1:8080, the rest → 127.0.0.1:8081 |
| api-medlink | `api`: production API, Keycloak, both databases | 16.171.194.155 | 172.31.33.183 | none; port 8080 open only to the front host's private IP |
| frontend-medlink | `front`: production front and TLS edge | 51.20.3.234 | 172.31.40.60 | Caddy `https://51.20.3.234.sslip.io`: `/api/*` → 172.31.33.183:8080, the rest → 127.0.0.1:8081 |
| vault | secrets (see [vault.md](vault.md)) | 13.48.56.245 | 172.31.44.172 | Caddy → Vault |

The public IPs change when an instance is stopped, unless Elastic IPs are attached: attach them before any stop or
resize, then update this table, the Caddy names (`<ip>.sslip.io`) and the pinned host keys.

## Who logs in, and how

- **`ubuntu`**: the admin, with the instance's EC2 launch key (`.pem`). For emergencies and set-up only. The keys
  never go into git, a chat, or a GitHub secret.
- **`deploy`**: the GitHub deploys only, with one ed25519 key per environment (staging, production), marked
  `restrict` (no terminal, no forwarding). Its private key is the environment's `DEPLOY_SSH_KEY` secret.
- No password logins, no root login, no other user (`/etc/ssh/sshd_config.d/10-medlink.conf`).

`deploy` is in the `docker` group, which is root in practice. What it buys is a key that is used by GitHub only and
can be replaced without touching the admin access.

## Prepare a host (once, then again after any change to `deploy/`)

1. **The deploy key of the environment**, made once per environment on an admin's machine. The private key goes to
   the repo owner for the GitHub secret, then is deleted locally:
   ```sh
   ssh-keygen -t ed25519 -N "" -C medlink-deploy-staging -f medlink-deploy-staging
   ```
2. **On the host**, from a copy of `deploy/` and the `.pub` file:
   ```sh
   scp -r deploy medlink-deploy-staging.pub ubuntu@<host>:
   ssh ubuntu@<host> sudo sh deploy/prepare-host.sh staging medlink-deploy-staging.pub   # or api / front
   ```
   It refuses (and changes nothing) when it is not root, the role is unknown, the key is not one ed25519 public key,
   Docker is missing, or `/etc/docker/daemon.json` already holds other settings (then add the log options by hand:
   `"log-driver": "json-file", "log-opts": {"max-size": "10m", "max-file": "3"}`).
3. **Check**, from the admin's machine: `ssh -i medlink-deploy-staging deploy@<host> docker ps` works, and
   `ssh -o PreferredAuthentications=password deploy@<host>` is refused.
4. **The host key for GitHub** (`DEPLOY_KNOWN_HOSTS`): `ssh-keyscan -t ed25519 <host>`. Check its fingerprint
   (`ssh-keygen -lf`) against the one seen at the first admin login, or against the instance's console output, before
   giving it to the repo owner.

What it sets up, per role:

| Item | staging | api | front |
|---|---|---|---|
| user `deploy`, docker group, one restricted key | ✓ | ✓ | ✓ |
| `/opt/medlink/api` (deploy 750) | ✓ | ✓ | |
| `/opt/medlink/front` (deploy 750) | ✓ | | ✓ |
| Docker logs rotated, 10 MB × 3 per container | ✓ | ✓ | ✓ |
| security updates, daily, no automatic reboot | ✓ | ✓ | ✓ |
| SSH: keys only, no root, users `ubuntu` and `deploy` | ✓ | ✓ | ✓ |
| daily database backup, 02:15 UTC, `/var/backups/medlink` | ✓ | ✓ | |

`sudo sshd -t` checks the SSH settings before they are used: a rejected drop-in is removed and SSH stays as it was.

## Database backups on the hosts

The job (`/usr/local/bin/medlink-db-backup`, from `deploy/db-backup-job.sh`) runs as `deploy` every day at 02:15 UTC.
It uses the deployed `scripts/db-backup.sh` (GitHub #67, runbook [backup-restore.md](backup-restore.md)) and the
passphrase the deploy writes from Vault to `/opt/medlink/api/.backup.env`. Until the first deploy it logs
`not configured yet` and stops. It keeps 7 daily and 4 weekly (Sunday) copies. Logs: `/var/log/medlink/db-backup.log`,
rotated weekly. Still to do: the off-host copy (an S3 bucket from the repo owner).

## Change or revoke a deploy key

Make a new key (step 1), run `prepare-host.sh` again with the new `.pub`: it replaces the old key, which stops working
at once. Then the repo owner updates `DEPLOY_SSH_KEY`.

## Notes

- Updates do not reboot the host. When `/var/run/reboot-required` exists, reboot in a quiet moment. **Today the
  containers do not come back on their own after a reboot**: `docker-compose.yml` has no `restart:` policy (to add
  with the deploy changes, MLink-169); until then, run the deploy again or `docker compose up -d` in
  `/opt/medlink/api`. Never reboot the vault host without someone able to unseal it.
- Test: `bash deploy/test/prepare-host.test.sh` (Docker; about three minutes). It runs in CI.
