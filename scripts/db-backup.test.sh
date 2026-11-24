#!/usr/bin/env bash
# Tests db-backup.sh and db-restore.sh offline (GitHub #67). A fake docker stands in for the two database containers:
# pg_dump prints a fake dump, pg_restore records what it receives. gpg is the real one (wrapped to record its
# arguments), so encryption, integrity and the round trip are real. Checks: the scripts refuse to run without a
# passphrase or outside their rules, never print the passphrase nor pass it as an argument, never write inside the
# repository, leave no partial file, and never feed a wrong or damaged backup to the database.
set -uo pipefail

SCRIPTS="$(cd "$(dirname "$0")" && pwd -P)"
REPO="$(cd "$SCRIPTS/.." && pwd -P)"
BACKUP="$SCRIPTS/db-backup.sh"
RESTORE="$SCRIPTS/db-restore.sh"
REAL_GPG="$(command -v gpg)" || { echo "FAIL: gpg is needed for this test" >&2; exit 1; }
WORK="$(mktemp -d)"
touch "$WORK/start"
# Backups this run wrote into the repository (only if its guard is broken): counted, then removed on exit
stray_in_repo() { find "$REPO" "$REPO/docs" -maxdepth 1 -name 'medlink-*.dump.gpg*' -newer "$WORK/start" "$@"; }
trap 'stray_in_repo -delete; rm -rf "$WORK"' EXIT
CALLS="$WORK/docker-calls"
GPG_CALLS="$WORK/gpg-calls"
PASSPHRASE='test-only-backup-passphrase-0123456789'

mkdir "$WORK/bin" "$WORK/out"
# docker compose ... exec -T <service> sh -c '<command>'
cat > "$WORK/bin/docker" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$DOCKER_CALLS"
service=''
while [ $# -gt 0 ]; do
    if [ "$1" = exec ]; then shift; [ "${1:-}" = -T ] && shift; service="${1:-}"; fi
    shift
done
case "$(tail -n 1 "$DOCKER_CALLS")" in
    *pg_dump*)
        printf 'PGDMP fake dump of %s\n' "$service"
        [ "${FAKE_DUMP_FAILS:-}" = "$service" ] && exit 1
        exit 0 ;;
    *pg_restore*) cat > "$FAKE_RESTORED/$service"; exit 0 ;;
esac
exit 0
EOF
cat > "$WORK/bin/gpg" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "\$GPG_CALLS"
exec "$REAL_GPG" "\$@"
EOF
chmod +x "$WORK/bin/docker" "$WORK/bin/gpg"
mkdir "$WORK/restored"

failures=0
cases=0
fail() { echo "FAIL: $1" >&2; failures=$((failures + 1)); }

OUT_TEXT=''
STATUS=0
# $1 script, then its arguments; the environment is given with env before it (see the calls below)
call() {
    : > "$CALLS"
    : > "$GPG_CALLS"
    OUT_TEXT="$(PATH="$WORK/bin:$PATH" DOCKER_CALLS="$CALLS" GPG_CALLS="$GPG_CALLS" FAKE_RESTORED="$WORK/restored" \
        "$@" 2>&1)"
    STATUS=$?
    cases=$((cases + 1))
}

# Nothing printed, passed to docker or passed to gpg as an argument may contain the passphrase
no_leak() {
    case "$OUT_TEXT" in *"$PASSPHRASE"*) fail "$1: the passphrase was printed" ;; esac
    if grep -qF -- "$PASSPHRASE" "$CALLS" "$GPG_CALLS"; then fail "$1: the passphrase was passed as an argument"; fi
}

# $1 name, $2 expected exit code; a refusal (2) must happen before any docker call
expect() {
    if [ "$STATUS" != "$2" ]; then
        fail "$1: expected exit $2, got $STATUS: $OUT_TEXT"
    elif [ "$2" = 2 ] && [ -s "$CALLS" ]; then
        fail "$1: refused, but docker was called first: $(head -n 1 "$CALLS")"
    fi
    no_leak "$1"
}

files_in() { find "$1" -type f | wc -l | tr -d ' '; }

# ---------------------------------------------------------------- backup: refusals
call env -u BACKUP_PASSPHRASE bash "$BACKUP" "$WORK/out"
expect 'backup without BACKUP_PASSPHRASE' 2
call env BACKUP_PASSPHRASE= bash "$BACKUP" "$WORK/out"
expect 'backup with an empty BACKUP_PASSPHRASE' 2
call env BACKUP_PASSPHRASE=short-passphrase bash "$BACKUP" "$WORK/out"
expect 'backup with a passphrase under 20 characters' 2
call env BACKUP_PASSPHRASE="$PASSPHRASE" bash "$BACKUP"
expect 'backup without an output directory' 2
call env BACKUP_PASSPHRASE="$PASSPHRASE" bash "$BACKUP" "$WORK/missing"
expect 'backup into a directory that does not exist' 2
[ -e "$WORK/missing" ] && fail 'backup created the missing output directory'
call env BACKUP_PASSPHRASE="$PASSPHRASE" bash "$BACKUP" "$REPO/docs"
expect 'backup into the repository' 2
call env BACKUP_PASSPHRASE="$PASSPHRASE" bash "$BACKUP" "$REPO"
expect 'backup into the repository root' 2
[ "$(files_in "$WORK/out")" = 0 ] || fail 'a refused backup wrote a file'
stray_in_repo -print | grep -q . && fail "a backup file was written in the repository: $(stray_in_repo -print | head -n 1)"

# ---------------------------------------------------------------- backup: both databases, encrypted
call env BACKUP_PASSPHRASE="$PASSPHRASE" bash "$BACKUP" "$WORK/out"
expect 'backup of both databases' 0
app_file="$(find "$WORK/out" -name 'medlink-app-*.dump.gpg')"
kc_file="$(find "$WORK/out" -name 'medlink-keycloak-*.dump.gpg')"
if [ -z "$app_file" ] || [ -z "$kc_file" ] || [ "$(files_in "$WORK/out")" != 2 ]; then
    fail "backup should write exactly one app and one keycloak file: $(ls "$WORK/out")"
else
    [ "${app_file#*medlink-app-}" = "${kc_file#*medlink-keycloak-}" ] ||
        fail 'both files of one run should carry the same UTC time'
    grep -q 'fake dump' "$app_file" "$kc_file" && fail 'a backup file holds the dump in clear'
    grep -q 'exec -T postgres ' "$CALLS" || fail 'the app database (service postgres) was not dumped'
    grep -q 'exec -T keycloak-db ' "$CALLS" || fail 'the Keycloak database (service keycloak-db) was not dumped'
    if [ "$(uname -s)" = Linux ]; then
        for f in "$app_file" "$kc_file"; do
            [ "$(stat -c %a "$f")" = 600 ] || fail "$f should be readable by its owner only, is $(stat -c %a "$f")"
        done
    fi
fi

call env BACKUP_PASSPHRASE="$PASSPHRASE" FAKE_DUMP_FAILS=keycloak-db bash "$BACKUP" "$WORK/out"
expect 'backup when a dump fails' 1
find "$WORK/out" -name '*.part' | grep -q . && fail 'a failed backup left a partial file'
[ "$(find "$WORK/out" -name 'medlink-keycloak-*' | wc -l | tr -d ' ')" = 1 ] ||
    fail 'a failed Keycloak dump must not leave a Keycloak file'

# ---------------------------------------------------------------- restore
if [ -n "$app_file" ] && [ -n "$kc_file" ]; then
    call env BACKUP_PASSPHRASE="$PASSPHRASE" bash "$RESTORE" app "$app_file"
    expect 'restore without --yes-overwrite' 2
    call env BACKUP_PASSPHRASE="$PASSPHRASE" bash "$RESTORE" billing "$app_file" --yes-overwrite
    expect 'restore of an unknown database' 2
    call env -u BACKUP_PASSPHRASE bash "$RESTORE" app "$app_file" --yes-overwrite
    expect 'restore without BACKUP_PASSPHRASE' 2
    call env BACKUP_PASSPHRASE="$PASSPHRASE" bash "$RESTORE" app "$WORK/out/none.dump.gpg" --yes-overwrite
    expect 'restore of a file that does not exist' 2

    call env BACKUP_PASSPHRASE='a-wrong-passphrase-0123456789' bash "$RESTORE" app "$app_file" --yes-overwrite
    expect 'restore with a wrong passphrase' 1
    [ -s "$CALLS" ] && fail 'a backup that does not decrypt reached docker'

    damaged="$WORK/damaged.dump.gpg"
    cp "$app_file" "$damaged"
    size="$(wc -c < "$damaged")"
    printf '\377\377\377\377' | dd of="$damaged" bs=1 seek=$((size / 2)) conv=notrunc 2>/dev/null
    call env BACKUP_PASSPHRASE="$PASSPHRASE" bash "$RESTORE" app "$damaged" --yes-overwrite
    expect 'restore of a damaged backup' 1
    [ -s "$CALLS" ] && fail 'a damaged backup reached docker'

    call env BACKUP_PASSPHRASE="$PASSPHRASE" bash "$RESTORE" app "$app_file" --yes-overwrite
    expect 'restore of the app database' 0
    [ "$(cat "$WORK/restored/postgres" 2>/dev/null)" = 'PGDMP fake dump of postgres' ] ||
        fail 'the app database did not receive its own dump'
    grep -q 'pg_restore' "$CALLS" && ! grep -q -- '--single-transaction' "$CALLS" &&
        fail 'the restore should run in one transaction (all or nothing)'

    call env BACKUP_PASSPHRASE="$PASSPHRASE" bash "$RESTORE" keycloak "$kc_file" --yes-overwrite
    expect 'restore of the Keycloak database' 0
    [ "$(cat "$WORK/restored/keycloak-db" 2>/dev/null)" = 'PGDMP fake dump of keycloak-db' ] ||
        fail 'the Keycloak database did not receive its own dump'
fi

if [ "$failures" -gt 0 ]; then
    echo "$failures failure(s) in $cases cases" >&2
    exit 1
fi
echo "db backup and restore: $cases cases passed"
