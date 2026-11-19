#!/usr/bin/env bash
# Tests the server deploy files (MLink-169): deploy/render-env.py, the per-environment settings and the compose overlay.
# Needs docker. Usage: bash deploy/test/render-env.test.sh
# shellcheck disable=SC2015  # "check && pass || fail": pass only echoes and always succeeds
set -euo pipefail
docker() { MSYS_NO_PATHCONV=1 command docker "$@"; }

HERE="$(cd "$(dirname "$0")" && pwd -P)"
DEPLOY="$(cd "$HERE/.." && pwd -P)"
ROOT="$(cd "$DEPLOY/.." && pwd -P)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FAILED=0
fail() { echo "FAIL: $*" >&2; FAILED=$((FAILED + 1)); }
pass() { echo "ok:   $*"; }
host_path() { if command -v cygpath > /dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

for f in render-env.py docker-compose.server.yml env/staging.env env/production.env; do
    [ -f "$DEPLOY/$f" ] && pass "deploy/$f exists" || fail "deploy/$f is missing"
done
[ "$FAILED" -eq 0 ] || { echo "server deploy files: $FAILED check(s) failed"; exit 1; }

render() { docker run --rm -i -v "$(host_path "$DEPLOY"):/deploy:ro" python:3.13-alpine python3 /deploy/render-env.py "$@"; }
FULL='{"POSTGRES_PASSWORD":"pg-value","APP_DB_PASSWORD":"app-value","KEYCLOAK_DB_PASSWORD":"kc-db-value","KC_BFF_CLIENT_SECRET":"bff-value","KC_ADMIN_CLIENT_SECRET":"adm-value","KEYCLOAK_ADMIN_PASSWORD":"kc-adm-value","KC_DEV_USERS_PASSWORD":"dev-value","APP_MFA_ENCRYPTION_KEY":"mfa+/=","APP_REPORT_ENCRYPTION_KEY":"rep+/=","BACKUP_PASSPHRASE":"backup-value","MAIL_PASSWORD":"mail-value"}'

printf '%s' "$FULL" | render env /deploy/env/staging.env > "$WORK/out.env" 2> "$WORK/out.err" && pass "renders a complete .env" || fail "render failed: $(cat "$WORK/out.err")"
grep -q "^POSTGRES_PASSWORD='pg-value'$" "$WORK/out.env" && pass "secrets are single-quoted" || fail "secret line wrong"
grep -q "^APP_MFA_ENCRYPTION_KEY='mfa+/='$" "$WORK/out.env" && pass "base64 values are kept whole" || fail "base64 value changed"
grep -q '^SPRING_PROFILES_ACTIVE=staging$' "$WORK/out.env" && pass "settings are included" || fail "settings missing"
grep -q 'backup-value' "$WORK/out.env" && fail "the backup passphrase reached .env" || pass "the backup passphrase stays out of .env"
printf '%s' "$FULL" | render backup > "$WORK/backup.env" 2> /dev/null
[ "$(cat "$WORK/backup.env")" = "BACKUP_PASSPHRASE='backup-value'" ] && pass "backup mode writes only the passphrase" || fail "backup output wrong"

missing='{"POSTGRES_PASSWORD":"pg-value"}'
if printf '%s' "$missing" | render env /deploy/env/staging.env > "$WORK/m.out" 2> "$WORK/m.err"; then fail "a missing secret was accepted"; else pass "a missing secret stops the deploy"; fi
grep -q 'APP_DB_PASSWORD' "$WORK/m.err" && pass "the error names the missing key" || fail "error does not name the key"
grep -q 'pg-value' "$WORK/m.err" "$WORK/m.out" && fail "a value was printed" || pass "no value is printed on error"

bad="${FULL/pg-value/it\'s}"
if printf '%s' "$bad" | render env /deploy/env/staging.env > /dev/null 2> "$WORK/q.err"; then fail "a quote in a value was accepted"; else pass "a quote in a value is refused"; fi
bad="${FULL/pg-value/two\\nlines}"
if printf '%s' "$bad" | render env /deploy/env/staging.env > /dev/null 2>&1; then fail "a newline in a value was accepted"; else pass "a newline in a value is refused"; fi

nomail="${FULL/,\"MAIL_PASSWORD\":\"mail-value\"/}"
printf '%s' "$nomail" | render env /deploy/env/staging.env > "$WORK/n.env" 2> "$WORK/n.err" && pass "a missing mail password does not stop the deploy" || fail "missing mail password stopped the deploy"
grep -q 'MAIL_PASSWORD' "$WORK/n.err" && pass "a missing mail password is reported" || fail "missing mail password not reported"

for e in staging production; do
    f="$DEPLOY/env/$e.env"
    grep -qE 'PASSWORD=|SECRET=|ENCRYPTION_KEY=|PASSPHRASE=' "$f" && fail "$e.env holds a secret key" || pass "$e.env holds no secret"
    grep -q '^KEYCLOAK_START_MODE=start$' "$f" && pass "$e: Keycloak in production mode" || fail "$e: Keycloak start mode"
    grep -qE "^APP_FRONTEND_URL=https://" "$f" && pass "$e: the front is on HTTPS" || fail "$e: front URL"
done
grep -q '^SPRING_PROFILES_ACTIVE=prod$' "$DEPLOY/env/production.env" && pass "production uses the prod profile" || fail "production profile"
grep -q '^API_BIND_ADDRESS=127\.0\.0\.1$' "$DEPLOY/env/staging.env" && pass "staging API on loopback" || fail "staging bind address"
grep -q '^API_BIND_ADDRESS=172\.31\.33\.183$' "$DEPLOY/env/production.env" && pass "production API on the private address" || fail "production bind address"

printf '%s' "$FULL" | render env /deploy/env/staging.env > "$WORK/compose.env"
echo 'APP_IMAGE=example/api@sha256:0000' >> "$WORK/compose.env"
(cd "$ROOT" && docker compose --env-file "$(host_path "$WORK/compose.env")" -f docker-compose.yml -f deploy/docker-compose.server.yml config) > "$WORK/config.yml" 2> "$WORK/config.err" &&
    pass "the overlay is valid" || fail "compose config: $(cat "$WORK/config.err")"
[ "$(grep -c 'published:' "$WORK/config.yml")" = 1 ] && pass "only the API publishes a port" || fail "published ports: $(grep -c 'published:' "$WORK/config.yml")"
grep -q 'host_ip: 127.0.0.1' "$WORK/config.yml" && pass "the API port is bound to the configured address" || fail "API port not bound"
[ "$(grep -c 'restart: unless-stopped' "$WORK/config.yml")" = 4 ] && pass "the four services come back after a reboot" || fail "restart policies: $(grep -c 'restart: unless-stopped' "$WORK/config.yml")"
grep -qE '^  (mailpit|prometheus):' "$WORK/config.yml" && fail "a local-only service would start on a server" || pass "mailpit and prometheus stay off the servers"

echo
if [ "$FAILED" -eq 0 ]; then echo "server deploy files: all checks passed"; else echo "server deploy files: $FAILED check(s) failed"; exit 1; fi
