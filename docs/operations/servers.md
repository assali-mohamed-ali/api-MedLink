# The MedLink hosts: preparation and access

MLink-171. Four EC2 instances in eu-north-1, Ubuntu 26.04, created by the repo owner. Only he has the AWS console.

| Host | Role | Elastic IP (2026-11-19) | Private IP | Edge |
|---|---|---|---|---|
| staging-server | `staging`: API, Keycloak, both databases and the front | 13.51.30.39 | 172.31.36.231 | Caddy `https://staging.med-l.ink` (and the old `https://13.51.30.39.sslip.io`): `/api/*`, `/livez`, `/readyz` → 127.0.0.1:8080, the rest → 127.0.0.1:8081 |
| api-medlink | `api`: production API, Keycloak, both databases | 13.50.114.144 | 172.31.33.183 | none; port 8080 open only to the front host's private IP |
| frontend-medlink | `front`: production front and TLS edge | 56.228.94.12 | 172.31.40.60 | Caddy `https://med-l.ink` (`www.` redirects to it; the old `https://56.228.94.12.sslip.io` still served): `/api/*` → 172.31.33.183:8080, the rest → 127.0.0.1:8081 |
| vault | secrets (see [vault.md](vault.md)) | 56.228.74.131 | 172.31.44.172 | Caddy → Vault |

Domain `med-l.ink` (Namecheap, since 2026-11-26): A records `@` and `www` → 56.228.94.12, `staging` → 13.51.30.39, on Elastic IPs. Caddy gets and renews the Let's Encrypt certificates; its previous configuration is kept as `/etc/caddy/Caddyfile.before-domain` on both hosts. The old `sslip.io` names go once every link and workflow variable uses the domain.

Host keys seen at the first admin login (2026-11-18, ED25519). They stay the same when the IP changes; check them
before trusting a host or filling `DEPLOY_KNOWN_HOSTS`:

| Host | SHA256 fingerprint |
|---|---|
| staging-server | `SHA256:SDzsgu9NJhpNiPOsoHtYdwC/pG9t6PbNs/tLeQTfsDo` |
| api-medlink | `SHA256:y80orC3jaIG8IGQ7SG4EBoXk9Xt0A8iZVH9y6kGDTw8` |
| frontend-medlink | `SHA256:r+uqLOeCDdK63zwNMhBU4aoRQAyPwO5jenFmrXLeyyY` |
| vault | `SHA256:5cbT/Vv58Nng9FJUy6/WY+v9kEL8GjwO8zsyqiYGqkA` |

The four addresses are Elastic IPs: they stay the same when an instance is stopped or resized.

Sizes on 2026-11-19: staging-server m7i-flex.large (8 GB RAM, 40 GB disk since 10:20 UTC), api-medlink c7i-flex.large
(4 GB RAM, 60 GB disk), frontend-medlink and vault t3.micro (8 GB disks).

## Who logs in, and how

- **`ubuntu`**: the admin, with the instance's EC2 launch key (`.pem`). For emergencies and set-up only. The keys
  never go into git, a chat, or a GitHub secret.
- **`deploy`**: the GitHub deploys only, with one ed25519 key per environment (staging, production), marked
  `restrict` (no terminal, no forwarding). Its private key is the API's `DEPLOY_SSH_KEY` secret; the dashboard also
  stores it in Vault as `medlink/<env>/front` and keeps the secret only as fallback.
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
at once. Then update `DEPLOY_SSH_KEY` on the API and replace the dashboard's Vault fields (a fresh `vault kv patch`,
see [vault.md](vault.md)) or its GitHub fallback secret before the next deploy.

## Grow a disk

The stack needs about 3 GB of images. After the repo owner enlarges a volume in the AWS console, a reboot lets Ubuntu
grow the root partition by itself (staging, 8 → 40 GB on 2026-11-19). Without a reboot, on the host:

```sh
sudo growpart /dev/nvme0n1 1 && sudo resize2fs /dev/nvme0n1p1 && df -h /
```

## Notes

- Updates do not reboot the host. When `/var/run/reboot-required` exists, reboot in a quiet moment. The containers come
  back on their own (`deploy/docker-compose.server.yml`). Never reboot the vault host without someone able to unseal it.
- Test: `bash deploy/test/prepare-host.test.sh` (Docker; about three minutes). It runs in CI.
