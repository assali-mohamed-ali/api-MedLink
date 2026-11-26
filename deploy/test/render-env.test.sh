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
    # The team mail account (MLink-172); a typo here makes Gmail refuse every app password with 535
    grep -q '^MAIL_USERNAME=medlinq2026@gmail\.com$' "$f" && grep -q "^MAIL_FROM='MedLink <medlinq2026@gmail\.com>'$" "$f" &&
        pass "$e: mail goes through the team account" || fail "$e: MAIL_USERNAME/MAIL_FROM is not medlinq2026@gmail.com"
done
grep -q '^SPRING_PROFILES_ACTIVE=prod$' "$DEPLOY/env/production.env" && pass "production uses the prod profile" || fail "production profile"
# The domain med-l.ink (2026-11-26): mail links and the public URL use it; CORS keeps the old sslip.io name until
# Caddy stops serving it
domain() { # $1 env file, $2 domain, $3 old sslip.io name
    grep -q "^APP_FRONTEND_URL=https://$2$" "$1" && grep -q "^APP_PUBLIC_URL=https://$2$" "$1" &&
        grep -q "^CORS_ALLOWED_ORIGINS=https://$2,https://$3$" "$1"
}
domain "$DEPLOY/env/production.env" 'med-l\.ink' '56\.228\.94\.12\.sslip\.io' &&
    pass "production: links and CORS on med-l.ink" || fail "production: APP_FRONTEND_URL/APP_PUBLIC_URL/CORS not on med-l.ink"
domain "$DEPLOY/env/staging.env" 'staging\.med-l\.ink' '13\.51\.30\.39\.sslip\.io' &&
    pass "staging: links and CORS on staging.med-l.ink" || fail "staging: APP_FRONTEND_URL/APP_PUBLIC_URL/CORS not on staging.med-l.ink"
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

# The deploy action and the CD jobs read Vault with GitHub's OIDC token, never a stored .env
ACTION="$ROOT/.github/actions/deploy-compose/action.yml"
CD="$ROOT/.github/workflows/cd.yml"
grep -q 'DEPLOY_ENV_FILE' "$ACTION" "$CD" && fail "a stored .env is still used" || pass "no stored .env any more"
grep -q 'audience=medlink-vault' "$ACTION" && pass "the action asks GitHub for a token for Vault" || fail "no OIDC token request"
grep -q 'auth/jwt-github/login' "$ACTION" && pass "the action logs in to Vault" || fail "no Vault login"
grep -q 'add-mask' "$ACTION" && pass "the secret values are masked in the logs" || fail "values not masked"
grep -q 'render-env.py env' "$ACTION" && grep -q 'render-env.py backup' "$ACTION" && pass "the action renders .env and .backup.env" || fail "render not called"
grep -q 'docker-compose.server.yml' "$ACTION" && pass "the action uses the server overlay" || fail "overlay not used"
grep -q 'scripts/db-backup.sh' "$ACTION" && grep -q 'scripts/db-restore.sh' "$ACTION" && pass "the backup scripts reach the host" || fail "backup scripts not copied"
grep -q 'exec -T postgres bash /docker-entrypoint-initdb.d/10-app-role.sh < /dev/null' "$ACTION" &&
    pass "the role script cannot swallow the rest of the rollout" || fail "compose exec reads the rollout script from stdin"
grep -q '^  deploy-staging:' "$CD" && pass "CD has a staging deploy" || fail "no deploy-staging job"
[ "$(grep -c 'id-token: write' "$CD")" -ge 3 ] && pass "the deploy jobs may request an OIDC token" || fail "id-token permission missing on a deploy job"
grep -q 'vault_role: api-staging' "$CD" && grep -q 'vault_role: api-production' "$CD" && pass "each environment uses its own Vault role" || fail "Vault roles"
# A manual staging run from master, main or staging builds and publishes the image: the staging deploy must then run too
job_if() { awk -v job="  $1:" '$0 == job { f = 1; next } f && /^  [a-z-]+:$/ { exit } f && /^    if:/ { print; exit }' "$CD"; }
dispatch_refs() { grep -oE "inputs\.target == 'staging' && \([^)]*\)|inputs\.target == 'staging' && github\.ref == '[^']*'" | grep -oE "refs/heads/[a-z]+" | sort -u | tr '\n' ' '; }
build_refs="$(job_if build-and-scan | dispatch_refs)"
deploy_refs="$(job_if deploy-staging | dispatch_refs)"
[ -n "$build_refs" ] && [ "$build_refs" = "$deploy_refs" ] &&
    pass "a manual staging run deploys from the same branches it builds from ($build_refs)" ||
    fail "manual staging run: builds from [$build_refs] but deploys from [$deploy_refs]"
grep -q '^  smoke-staging:' "$CD" && pass "CD smoke-tests staging" || fail "no staging smoke job"
grep -q '^  wait-for-frontend-staging:' "$CD" && pass "CD waits for the dashboard staging jobs" || fail "no cross-repository gate"
grep -q -- '--job deploy-staging' "$CD" && grep -q -- '--job smoke-staging' "$CD" &&
    pass "the gate checks both staging jobs" || fail "the gate does not check both jobs"
grep -q '^  deploy-prod-from-staging:' "$CD" && pass "CD deploys production from the staging digest" || fail "no automatic production job"
grep -q "vars.AUTO_DEPLOY_PROD != 'false'" "$CD" && pass "automatic production is on unless disabled" || fail "missing AUTO_DEPLOY_PROD switch"
grep -q "vars.AUTO_DEPLOY_PROD == 'false'" "$CD" && pass "the legacy promotion is switch-gated" || fail "legacy promotion not gated"
grep -q 'cancel-in-progress: false' "$CD" && pass "a newer run cannot cancel a deployment" || fail "concurrency cancels deployments"

echo
if [ "$FAILED" -eq 0 ]; then echo "server deploy files: all checks passed"; else echo "server deploy files: $FAILED check(s) failed"; exit 1; fi
