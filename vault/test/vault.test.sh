#!/usr/bin/env bash
# Tests the Vault configuration as code (MLink-168) against a throwaway Vault 1.21.0 in a container:
#   - setup.sh runs with the limited setup token (not root), twice, and changes nothing the second time;
#   - a GitHub OIDC token logs in only for its own repository and environment, reads only its own path, never writes;
#   - wrong repository, environment, audience, event or an expired token are refused;
#   - the audit log records requests without the secret values; seed-env.sh fills a path once and never overwrites.
# GitHub tokens are signed here with a throwaway key (openssl), replacing the GitHub discovery URL in the copy only.
# Needs docker and openssl. Usage: bash vault/test/vault.test.sh
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash: keep container paths as they are

HERE="$(cd "$(dirname "$0")" && pwd -P)"
VAULT_DIR="$(cd "$HERE/.." && pwd -P)"
IMAGE=hashicorp/vault:1.21.0
NAME="medlink-vault-test-$$"
WORK="$(mktemp -d)"
FAILED=0
cleanup() { docker rm -f "$NAME" > /dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; FAILED=$((FAILED + 1)); }
pass() { echo "ok:   $*"; }

# Mount path for docker on Windows (Git Bash) and Linux alike
host_path() { if command -v cygpath > /dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

docker run -d --name "$NAME" -e VAULT_DEV_ROOT_TOKEN_ID=root-test-only -e SKIP_SETCAP=1 \
    -v "$(host_path "$VAULT_DIR"):/medlink-vault:ro" "$IMAGE" server -dev > /dev/null
in_vault() { docker exec -i -e VAULT_ADDR=http://127.0.0.1:8200 "$@"; }
as_root() { in_vault -e VAULT_TOKEN=root-test-only "$NAME" vault "$@"; }
for _ in $(seq 1 30); do as_root status > /dev/null 2>&1 && break; sleep 1; done
as_root status > /dev/null || { echo "Vault did not start" >&2; exit 1; }
docker exec "$NAME" sh -c 'mkdir -p /var/log/vault && chown vault:vault /var/log/vault'

# 1. The setup token: only the medlink-setup policy, as the repo owner would create it
as_root policy write medlink-setup /medlink-vault/policies/medlink-setup.hcl > /dev/null
SETUP_TOKEN="$(as_root token create -policy=medlink-setup -ttl=1h -field=token)"
run_setup() { in_vault -e VAULT_TOKEN="$SETUP_TOKEN" "$NAME" sh /medlink-vault/setup.sh; }

if run_setup > "$WORK/setup1.log" 2>&1; then pass "setup.sh runs with the setup token"; else
    fail "setup.sh with the setup token"; cat "$WORK/setup1.log" >&2; fi
state() { as_root secrets list; as_root auth list; as_root audit list; as_root policy list;
    for r in api-staging api-production front-staging front-production; do as_root read -format=json "auth/jwt-github/role/$r"; done; }
state > "$WORK/state1" 2>&1
if run_setup > "$WORK/setup2.log" 2>&1; then pass "setup.sh runs a second time"; else
    fail "setup.sh second run"; cat "$WORK/setup2.log" >&2; fi
state > "$WORK/state2" 2>&1
diff -q "$WORK/state1" "$WORK/state2" > /dev/null && pass "second run changes nothing" || fail "second run changed the configuration"

# 2. Secrets to read, then the GitHub login switched to a local test key (same issuer as GitHub's)
for p in staging/api prod/api staging/front prod/front; do
    as_root kv put "medlink/$p" "MARKER=value-of-$p" "SECRET_VALUE=s3cr3t-$p-do-not-log" > /dev/null
done
openssl genrsa -out "$WORK/key.pem" 2048 2> /dev/null
openssl rsa -in "$WORK/key.pem" -pubout -out "$WORK/pub.pem" 2> /dev/null
docker cp "$(host_path "$WORK/pub.pem")" "$NAME:/tmp/pub.pem"
as_root write auth/jwt-github/config jwt_validation_pubkeys=@/tmp/pub.pem \
    bound_issuer=https://token.actions.githubusercontent.com > /dev/null

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
# $1 sub, $2 audience, $3 lifetime in seconds (negative: already expired)
jwt() {
    local now; now="$(date +%s)"
    local header payload
    header="$(printf '{"alg":"RS256","typ":"JWT","kid":"test"}' | b64url)"
    payload="$(printf '{"iss":"https://token.actions.githubusercontent.com","sub":"%s","aud":"%s","iat":%d,"nbf":%d,"exp":%d}' \
        "$1" "$2" "$((now - 60))" "$((now - 60))" "$((now + $3))" | b64url)"
    local sig; sig="$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -sign "$WORK/key.pem" | b64url)"
    printf '%s.%s.%s' "$header" "$payload" "$sig"
}
# $1 role, $2 jwt: prints a Vault token, fails when the login is refused
login() { in_vault "$NAME" vault write -field=token "auth/jwt-github/login" "role=$1" "jwt=$2" 2> /dev/null; }
AUD=medlink-vault
API=repo:assali-mohamed-ali/api-MedLink
FRONT=repo:assali-mohamed-ali/medlink-dashboard

# 3. What each environment may do
check_role() { # $1 role $2 sub $3 own path, then the paths it must not read
    local role="$1" sub="$2" own="$3"; shift 3
    local token
    if ! token="$(login "$role" "$(jwt "$sub" "$AUD" 300)")"; then fail "$role: login refused for $sub"; return; fi
    pass "$role: login for $sub"
    if in_vault -e VAULT_TOKEN="$token" "$NAME" vault kv get -field=MARKER "medlink/$own" > /dev/null 2>&1; then
        pass "$role: reads medlink/$own"; else fail "$role: cannot read medlink/$own"; fi
    local p
    for p in "$@"; do
        if in_vault -e VAULT_TOKEN="$token" "$NAME" vault kv get "medlink/$p" > /dev/null 2>&1; then
            fail "$role: reads medlink/$p"; else pass "$role: refused medlink/$p"; fi
    done
    if in_vault -e VAULT_TOKEN="$token" "$NAME" vault kv put "medlink/$own" MARKER=overwritten > /dev/null 2>&1; then
        fail "$role: writes medlink/$own"; else pass "$role: cannot write medlink/$own"; fi
    local ttl; ttl="$(in_vault -e VAULT_TOKEN="$token" "$NAME" vault token lookup -field=ttl 2> /dev/null || echo 0)"
    ttl="${ttl%s}"
    [ "${ttl:-0}" -gt 0 ] && [ "${ttl:-0}" -le 600 ] && pass "$role: token lives at most 10 minutes" ||
        fail "$role: token ttl $ttl s, expected 1..600"
}
check_role api-staging      "$API:environment:staging"      staging/api   prod/api staging/front prod/front
check_role api-production   "$API:environment:production"   prod/api      staging/api staging/front prod/front
check_role front-staging    "$FRONT:environment:staging"    staging/front prod/front staging/api prod/api
check_role front-production "$FRONT:environment:production" prod/front    staging/front staging/api prod/api

# 4. Logins that must be refused
refused() { # $1 what, $2 role, $3 jwt
    if login "$2" "$3" > /dev/null; then fail "login accepted: $1"; else pass "login refused: $1"; fi
}
refused "staging token on the production role"  api-production "$(jwt "$API:environment:staging" "$AUD" 300)"
refused "production token on the staging role"  api-staging    "$(jwt "$API:environment:production" "$AUD" 300)"
refused "front token on the API role"           api-staging    "$(jwt "$FRONT:environment:staging" "$AUD" 300)"
refused "another repository"                    api-staging    "$(jwt "repo:someone-else/api-MedLink:environment:staging" "$AUD" 300)"
refused "a pull request run"                    api-staging    "$(jwt "$API:pull_request" "$AUD" 300)"
refused "a branch run without environment"      api-staging    "$(jwt "$API:ref:refs/heads/staging" "$AUD" 300)"
refused "another audience"                      api-staging    "$(jwt "$API:environment:staging" "sigstore" 300)"
refused "an expired token"                      api-staging    "$(jwt "$API:environment:staging" "$AUD" -120)"
refused "a token signed with another key" api-staging "$(
    openssl genrsa -out "$WORK/other.pem" 2048 2> /dev/null; cp "$WORK/key.pem" "$WORK/key.bak"
    cp "$WORK/other.pem" "$WORK/key.pem"; jwt "$API:environment:staging" "$AUD" 300; cp "$WORK/key.bak" "$WORK/key.pem")"

# 5. The audit log records requests, never the values
docker exec "$NAME" sh -c 'test -s /var/log/vault/audit.log' && pass "audit log written" || fail "no audit log"
if docker exec "$NAME" grep -q 's3cr3t-' /var/log/vault/audit.log; then fail "a secret value is in clear in the audit log"
else pass "no secret value in clear in the audit log"; fi

# 6. The snapshot role can take snapshots and nothing else
SNAP="$(as_root token create -role=medlink-snapshot -field=token 2> /dev/null || true)"
if [ -n "$SNAP" ]; then
    caps="$(as_root token capabilities "$SNAP" sys/storage/raft/snapshot)"
    [ "$caps" = read ] && pass "snapshot token: read on sys/storage/raft/snapshot" || fail "snapshot token caps: $caps"
    caps="$(as_root token capabilities "$SNAP" medlink/data/prod/api)"
    [ "$caps" = deny ] && pass "snapshot token cannot read secrets" || fail "snapshot token on secrets: $caps"
else fail "no token from the medlink-snapshot role"; fi

# 7. seed-env.sh: fills the API secrets once, valid formats, never overwrites; refuses an unknown environment
seed() { in_vault -e VAULT_TOKEN="$SETUP_TOKEN" "$NAME" sh /medlink-vault/seed-env.sh "$@" < /dev/null; }
as_root kv metadata delete medlink/staging/api > /dev/null
if seed staging > "$WORK/seed1.log" 2>&1; then pass "seed-env.sh staging"; else fail "seed-env.sh staging"; cat "$WORK/seed1.log" >&2; fi
for k in POSTGRES_PASSWORD APP_DB_PASSWORD KEYCLOAK_DB_PASSWORD KEYCLOAK_ADMIN_PASSWORD KC_BFF_CLIENT_SECRET \
    KC_ADMIN_CLIENT_SECRET KC_DEV_USERS_PASSWORD APP_MFA_ENCRYPTION_KEY APP_REPORT_ENCRYPTION_KEY BACKUP_PASSPHRASE; do
    as_root kv get -field="$k" medlink/staging/api > /dev/null 2>&1 || fail "seed: $k missing"
done
for k in APP_MFA_ENCRYPTION_KEY APP_REPORT_ENCRYPTION_KEY; do
    n="$(as_root kv get -field="$k" medlink/staging/api | openssl base64 -d -A | wc -c | tr -d ' ')"
    [ "$n" = 32 ] && pass "seed: $k is 32 bytes in base64" || fail "seed: $k decodes to $n bytes"
done
pw="$(as_root kv get -field=KC_DEV_USERS_PASSWORD medlink/staging/api)"
case "$pw" in *[A-Z]*) ;; *) fail "seed: KC_DEV_USERS_PASSWORD has no upper case";; esac
case "$pw" in *[a-z]*) ;; *) fail "seed: KC_DEV_USERS_PASSWORD has no lower case";; esac
case "$pw" in *[0-9]*) ;; *) fail "seed: KC_DEV_USERS_PASSWORD has no digit";; esac
[ "${#pw}" -ge 12 ] && pass "seed: dev users password meets the realm policy" || fail "seed: password too short"
grep -qE 's3cr3t|[A-Za-z0-9+/]{40}' "$WORK/seed1.log" && fail "seed printed a value" || pass "seed prints no value"
before="$(as_root kv get -field=POSTGRES_PASSWORD medlink/staging/api)"
seed staging > "$WORK/seed2.log" 2>&1 || fail "seed-env.sh second run"
after="$(as_root kv get -field=POSTGRES_PASSWORD medlink/staging/api)"
[ "$before" = "$after" ] && pass "seed never overwrites a value" || fail "seed overwrote POSTGRES_PASSWORD"
if seed qa > /dev/null 2>&1; then fail "seed accepted environment qa"; else pass "seed refuses an unknown environment"; fi

echo
if [ "$FAILED" -eq 0 ]; then echo "vault configuration: all checks passed"; else echo "vault configuration: $FAILED check(s) failed"; exit 1; fi
