#!/usr/bin/env bash
# Encrypted backup of both PostgreSQL databases (GitHub #67): the app's (compose service postgres) and Keycloak's
# (keycloak-db), one after the other in the same run, so the pair can be restored together.
#
#   BACKUP_PASSPHRASE=... scripts/db-backup.sh <existing directory outside the repository>
#
# Run it where `docker compose` finds the stack (the repository for dev, the deploy directory on a server, or set
# COMPOSE_FILE). Each dump is taken inside its container with that container's own credentials (pg_dump in custom
# format), encrypted on the way out with gpg (AES-256, integrity-protected) and written as
# medlink-<app|keycloak>-<UTC time>.dump.gpg, readable by its owner only. Nothing in clear touches the disk. Each file
# is then decrypted in full and checked to be a pg_dump archive. Runbook: docs/operations/backup-restore.md.
#
# BACKUP_PASSPHRASE comes from the environment only (at least 20 characters): it is never an argument, never printed,
# and reaches gpg through a pipe. Exit codes: 0 done, 1 a step failed, 2 refused before starting.
set -euo pipefail
umask 077

die() { echo "db-backup: $2" >&2; exit "$1"; }

[ $# -eq 1 ] || die 2 'usage: BACKUP_PASSPHRASE=... db-backup.sh <output directory>'
[ -n "${BACKUP_PASSPHRASE:-}" ] || die 2 'BACKUP_PASSPHRASE must be set (environment only, never an argument)'
[ "${#BACKUP_PASSPHRASE}" -ge 20 ] || die 2 'BACKUP_PASSPHRASE must have at least 20 characters'
[ -d "$1" ] || die 2 "the output directory $1 does not exist: create it first, readable by the backup operator only"

REPO="$(cd "$(dirname "$0")/.." && pwd -P)"
OUT="$(cd -- "$1" && pwd -P)"
case "$OUT/" in
    "$REPO"/*) die 2 "the output directory $OUT is inside the repository: choose one outside it, off the Docker volumes" ;;
esac

# A private gpg home: no keyring, agent or passphrase cache of the operator is used or changed
GNUPGHOME="$(mktemp -d)"
export GNUPGHOME
PARTS=()
cleanup() {
    rm -f -- "${PARTS[@]+"${PARTS[@]}"}"
    gpgconf --homedir "$GNUPGHOME" --kill gpg-agent >/dev/null 2>&1 || true
    rm -rf -- "$GNUPGHOME"
}
trap cleanup EXIT

gpg_with_passphrase() {
    gpg --batch --quiet --no-symkey-cache --pinentry-mode loopback --passphrase-fd 3 "$@" \
        3< <(printf '%s' "$BACKUP_PASSPHRASE")
}

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

# $1 compose service, $2 name in the file
backup() {
    local final="$OUT/medlink-$2-$STAMP.dump.gpg"
    local part="$final.part"
    PARTS+=("$part")
    docker compose exec -T "$1" sh -c \
        'exec pg_dump --format=custom --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' |
        gpg_with_passphrase --symmetric --cipher-algo AES256 --s2k-digest-algo SHA512 --s2k-count 65011712 \
            --output - > "$part" ||
        die 1 "the dump of $2 (service $1) failed: no file written for it"
    # Read the whole file back: gpg checks its integrity at the end, pg_dump archives start with PGDMP
    local magic
    magic="$(gpg_with_passphrase --decrypt --output - -- "$part" | { head -c 5; cat > /dev/null; })" ||
        die 1 "the backup of $2 does not decrypt: no file written for it"
    [ "$magic" = PGDMP ] || die 1 "the backup of $2 is not a pg_dump archive: no file written for it"
    mv -- "$part" "$final"
    echo "db-backup: $2 -> $final ($(wc -c < "$final" | tr -d ' ') bytes)"
}

backup postgres app
backup keycloak-db keycloak
echo "db-backup: done ($STAMP)"
