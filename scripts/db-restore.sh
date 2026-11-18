#!/usr/bin/env bash
# Restores one database from a db-backup.sh file (GitHub #67). It replaces the current content of that database.
#
#   BACKUP_PASSPHRASE=... scripts/db-restore.sh <app|keycloak> <file.dump.gpg> --yes-overwrite
#
# Run it where `docker compose` finds the stack, with the database container up and its users stopped (the API for
# app, Keycloak for keycloak). The whole file is decrypted and checked first (integrity, pg_dump archive), so a wrong
# passphrase or a damaged file never reaches the database; then pg_restore runs inside the container, as the
# container's owner, in a single transaction: the database ends either fully restored or unchanged.
# Restore app and keycloak from the same run (same UTC time in both names). Runbook: docs/operations/backup-restore.md.
#
# BACKUP_PASSPHRASE comes from the environment only. Exit codes: 0 restored, 1 a step failed, 2 refused.
set -euo pipefail
umask 077

die() { echo "db-restore: $2" >&2; exit "$1"; }

usage='usage: BACKUP_PASSPHRASE=... db-restore.sh <app|keycloak> <file.dump.gpg> --yes-overwrite'
[ $# -ge 2 ] || die 2 "$usage"
case "$1" in
    app) SERVICE=postgres ;;
    keycloak) SERVICE=keycloak-db ;;
    *) die 2 "unknown database '$1': app or keycloak. $usage" ;;
esac
FILE="$2"
[ "${3:-}" = --yes-overwrite ] && [ $# -eq 3 ] ||
    die 2 "this replaces the current $1 database (service $SERVICE): add --yes-overwrite. $usage"
[ -n "${BACKUP_PASSPHRASE:-}" ] || die 2 'BACKUP_PASSPHRASE must be set (environment only, never an argument)'
[ -f "$FILE" ] || die 2 "no such backup file: $FILE"

GNUPGHOME="$(mktemp -d)"
export GNUPGHOME
cleanup() {
    gpgconf --homedir "$GNUPGHOME" --kill gpg-agent >/dev/null 2>&1 || true
    rm -rf -- "$GNUPGHOME"
}
trap cleanup EXIT

gpg_with_passphrase() {
    gpg --batch --quiet --no-symkey-cache --pinentry-mode loopback --passphrase-fd 3 "$@" \
        3< <(printf '%s' "$BACKUP_PASSPHRASE")
}

magic="$(gpg_with_passphrase --decrypt --output - -- "$FILE" 2>/dev/null | { head -c 5; cat > /dev/null; })" ||
    die 1 "$FILE does not decrypt (wrong passphrase or damaged file): the database was not touched"
[ "$magic" = PGDMP ] || die 1 "$FILE is not a pg_dump archive: the database was not touched"

echo "db-restore: restoring $1 (service $SERVICE) from $FILE"
gpg_with_passphrase --decrypt --output - -- "$FILE" |
    docker compose exec -T "$SERVICE" sh -c \
        'exec pg_restore --clean --if-exists --no-owner --single-transaction --exit-on-error --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' ||
    die 1 "the restore of $1 failed: the transaction was rolled back, the database is unchanged"
echo "db-restore: $1 restored"
