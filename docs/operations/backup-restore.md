# Backup and restore of the two databases

GitHub #67 (MLink-166). MedLink keeps its data in two PostgreSQL databases, each in its own container and volume:

| Database | Compose service | Volume | Holds |
|---|---|---|---|
| app | `postgres` | `pgdata` | patients, practitioners, appointments, reports (sealed), the audit trail |
| Keycloak | `keycloak-db` | `keycloak-db-data` | identities, password hashes, roles, staff codes, the realm |

A volume is not a backup: it dies with the host or its disk, and a `docker compose down -v` deletes it. Both
databases must come back together: the app's `users.id` is the Keycloak user id.

## Policy

| Item | Value |
|---|---|
| Frequency | Daily, 02:15 UTC, both databases in the same run |
| Retention | 7 daily + 4 weekly (the Sunday run) |
| RPO (data that can be lost) | 24 h: one run a day, no WAL archiving |
| RTO (time to restore both) | about 1 minute on the dev stack (drill below: restore 2 s, then 53 s until the API passes its checks); plan 1 hour, with the download from the off-host store |
| Encryption | gpg, AES-256, integrity-protected; passphrase of 20 characters or more |
| Passphrase | in the team's secret store (two people can read it), never on the host in clear outside root's env file, never in git |
| Where | an off-host store (object storage with versioning, or another machine), not the Docker host nor its volumes; the host keeps the last 7 days for fast restores |
| Who can read | the backup operator (files `600`, directory `700`) and the off-host store's admins; a file is useless without the passphrase |

## How it works

`scripts/db-backup.sh <directory>` takes `pg_dump --format=custom` inside each container, with that container's own
credentials, so no database password is on the host's command line. The dump is encrypted on its way out (nothing in
clear touches the disk), written as `medlink-<app|keycloak>-<UTC time>.dump.gpg`, then decrypted in full once to
check it (integrity, and that it is a pg_dump archive). A failed step leaves no file for that database and exits 1.

`scripts/db-restore.sh <app|keycloak> <file> --yes-overwrite` decrypts and checks the whole file first, so a wrong
passphrase or a damaged file never reaches the database; then `pg_restore` runs inside the container in a single
transaction: the database ends fully restored or unchanged.

Both read `BACKUP_PASSPHRASE` from the environment only. They refuse to write inside the repository, need an existing
output directory, and run where `docker compose` finds the stack (the deploy directory on a server, or set
`COMPOSE_FILE`).

**Consistency.** Each dump is a consistent snapshot of its database. The two are taken one after the other, seconds
apart, app first: anything created in between exists only in Keycloak's dump. A Keycloak user without an app row is
already handled: the API creates the `users` row at that person's first login (GitHub #29). The opposite order could
leave app rows pointing to Keycloak users that the restore does not bring back.

## Set up on a server (once)

```sh
sudo install -d -m 700 -o "$USER" /var/backups/medlink /var/backups/medlink/weekly
sudo install -m 755 scripts/db-backup.sh scripts/db-restore.sh /opt/medlink/
# root-only file with one line: BACKUP_PASSPHRASE=<from the secret store>
sudo install -m 600 /dev/null /etc/medlink/backup.env && sudoedit /etc/medlink/backup.env
```

The host needs `gpg` (`apt-get install gnupg`). Crontab of the backup operator (`crontab -e`):

```cron
15 2 * * * /usr/bin/flock -n /tmp/medlink-backup.lock /bin/bash -c 'set -a; . /etc/medlink/backup.env; set +a; cd /srv/medlink && /opt/medlink/db-backup.sh /var/backups/medlink && if [ "$(date -u +\%u)" = 7 ]; then cp -p /var/backups/medlink/medlink-*-"$(date -u +\%Y\%m\%d)"T*.dump.gpg /var/backups/medlink/weekly/; fi; find /var/backups/medlink -maxdepth 1 -name "medlink-*.dump.gpg" -mtime +7 -delete; find /var/backups/medlink/weekly -name "medlink-*.dump.gpg" -mtime +28 -delete' >> /var/log/medlink-backup.log 2>&1
```

Then copy each run off the host with the store's own tool (for example `rclone copy /var/backups/medlink
remote:medlink-backups`), with versioning or object lock on the store, and a lifecycle rule matching the retention.
Alert when `/var/log/medlink-backup.log` has no `db-backup: done` line for the day.

## Restore both databases

1. Pick one run: the `app` and `keycloak` files with **the same UTC time**. Copy them back from the off-host store
   if needed.
2. Stop the services that use the databases, keep the databases up:
   `docker compose stop app keycloak`
3. Restore, with `BACKUP_PASSPHRASE` set from the secret store:
   ```sh
   /opt/medlink/db-restore.sh app      /var/backups/medlink/medlink-app-<time>.dump.gpg      --yes-overwrite
   /opt/medlink/db-restore.sh keycloak /var/backups/medlink/medlink-keycloak-<time>.dump.gpg --yes-overwrite
   ```
   On a new host, start the databases first (`docker compose up -d --wait postgres keycloak-db`): the app database
   then has its runtime role `medlink_app` (created by `docker/postgres/initdb/10-app-role.sh`), which the restored
   grants need.
4. Start everything: `docker compose up -d`.
5. Verify (below). Write the date, the run restored and the time taken in the drill log.

## Verify a restore

- The API answers `/readyz` with `UP` and Flyway reports no pending migration (the API would not start otherwise).
- App database: `docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "select
  max(installed_rank), count(*) from flyway_schema_history"'`, and the row counts of `users`, `appointments` and
  `audit_entries` match the source at backup time (the audit trail only grows).
- Keycloak: a known account logs in through the API (dev: `patient.dev`), and a staff account still asks for its code.
- The newest `audit_entries` row is from just before the backup time.

## Troubleshooting

- **`role "medlink_app" does not exist`** (app restore): the dump grants rights to the API's runtime role (GitHub
  #57), and this database was started without the script that creates it. Nothing was changed (one transaction).
  Create the role, then restore again:
  `docker compose exec -T postgres bash /docker-entrypoint-initdb.d/10-app-role.sh`.
- **`does not decrypt`**: wrong passphrase, or a damaged or truncated file; the database was not touched. Try the
  copy from the off-host store. A file whose passphrase is lost cannot be recovered: before a drill or a restore,
  check that the passphrase can be read from the secret store, and never delete a database before that.

## Drill log

| Date | Where | Run restored | Backup (both) | Restore (both) | Checks |
|---|---|---|---|---|---|
| 2026-11-18 | dev stack (Windows, Docker Desktop), both volumes deleted with `docker compose down -v` | `20261118T174159Z` | 3 s, 22 kB + 91 kB | 2 s, then the API up and checked in 53 s | marker rows back in both databases; users, Flyway history and the Keycloak id of `patient.dev` unchanged; `setup-dev -KeepData`: `patient.dev` logs in, `practitioner.dev` asked for its code, a signup works. First attempt refused, `role "medlink_app" does not exist` (see Troubleshooting): rolled back, nothing restored |

Repeat the drill every quarter, and after any change to the database images or these scripts.

## Limits

- RPO 24 h. A shorter one needs WAL archiving (point-in-time recovery), not set up.
- The off-host copy and its alert are set up on the server, not in this repository.
- Rotating the passphrase: new runs use the new one; keep the old one in the secret store until the last file that
  uses it has expired (28 days).
