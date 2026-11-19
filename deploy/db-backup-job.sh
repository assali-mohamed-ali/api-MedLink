#!/bin/sh
# The daily backup job of a host with databases (GitHub #67, MLink-171), installed as /usr/local/bin/medlink-db-backup
# by deploy/prepare-host.sh and run by cron as user deploy. It backs up both databases with the deployed
# scripts/db-backup.sh, keeps a weekly copy on Sundays, and deletes daily copies after 7 days, weekly after 28.
# The deploy writes /opt/medlink/api/.backup.env (BACKUP_PASSPHRASE, from Vault); until then the job refuses to run.
set -eu
umask 077

API_DIR="${API_DIR:-/opt/medlink/api}"
OUT="${BACKUP_DIR:-/var/backups/medlink}"
ENV_FILE="$API_DIR/.backup.env"
SCRIPT="$API_DIR/scripts/db-backup.sh"

if [ ! -f "$ENV_FILE" ] || [ ! -x "$SCRIPT" ]; then
    echo "medlink-db-backup: not configured yet ($ENV_FILE and $SCRIPT come with the first deploy)" >&2
    exit 1
fi
set -a
# shellcheck disable=SC1090  # written by the deploy
. "$ENV_FILE"
set +a

cd "$API_DIR"
"$SCRIPT" "$OUT"
if [ "$(date -u +%u)" = 7 ]; then
    for f in "$OUT"/medlink-*-"$(date -u +%Y%m%d)"T*.dump.gpg; do
        [ -f "$f" ] && cp -p "$f" "$OUT/weekly/"
    done
fi
find "$OUT" -maxdepth 1 -name 'medlink-*.dump.gpg' -mtime +7 -delete
find "$OUT/weekly" -maxdepth 1 -name 'medlink-*.dump.gpg' -mtime +28 -delete
