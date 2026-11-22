#!/usr/bin/env bash
# Tests the Vault configuration as code (MLink-168) against a throwaway Vault 1.21.0 in a container:
#   - setup.sh runs with the limited setup token (not root), twice, and changes nothing the second time;
#   - a GitHub OIDC token logs in only for its own repository and environment, from the CD workflow on its own
#     branches or tags (push or manual run), reads only its own path, never writes;
#   - wrong repository, environment, workflow, ref, audience, event or an expired token are refused;
#   - the audit log records requests without the secret values; seed-env.sh and seed-front.sh fill a path once and never overwrite.
# GitHub tokens are signed here with a throwaway key (openssl), replacing the GitHub discovery URL in the copy only.
# Needs docker and openssl. Usage: bash vault/test/vault.test.sh
# shellcheck disable=SC2015  # "check && pass || fail": pass only echoes and always succeeds
set -euo pipefail
# Git Bash: keep container paths as they are for docker only (openssl still needs its host paths translated)
docker() { MSYS_NO_PATHCONV=1 command docker "$@"; }

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
    for r in api-staging api-production front-staging front-production; do
        as_root read -format=json "auth/jwt-github/role/$r" | grep -v '"request_id"'; done; }
state > "$WORK/state1" 2>&1
if run_setup > "$WORK/setup2.log" 2>&1; then pass "setup.sh runs a second time"; else
    fail "setup.sh second run"; cat "$WORK/setup2.log" >&2; fi
state > "$WORK/state2" 2>&1
diff "$WORK/state1" "$WORK/state2" > /dev/null && pass "second run changes nothing" || fail "second run changed the configuration"

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
# $1 sub, $2 audience, $3 lifetime in seconds (negative: already expired), $4 more claims (see claims)
jwt() {
    local now; now="$(date +%s)"
    local header payload
    header="$(printf '{"alg":"RS256","typ":"JWT","kid":"test"}' | b64url)"
    payload="$(printf '{"iss":"https://token.actions.githubusercontent.com","sub":"%s","aud":"%s","iat":%d,"nbf":%d,"exp":%d%s}' \
        "$1" "$2" "$((now - 60))" "$((now - 60))" "$((now + $3))" "${4:-}" | b64url)"
    local sig; sig="$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -sign "$WORK/key.pem" | b64url)"
    printf '%s.%s.%s' "$header" "$payload" "$sig"
}
# $1 role, $2 jwt: prints a Vault token, fails when the login is refused
login() { in_vault "$NAME" vault write -field=token "auth/jwt-github/login" "role=$1" "jwt=$2" 2> /dev/null; }
AUD=medlink-vault
API=repo:assali-mohamed-ali/api-MedLink
FRONT=repo:assali-mohamed-ali/medlink-dashboard
# GitHub's immutable subject claims for these repositories (Settings > Actions > OIDC).
API_IMMUTABLE=repo:assali-mohamed-ali@110344042/api-MedLink@1410478132
FRONT_IMMUTABLE=repo:assali-mohamed-ali@110344042/medlink-dashboard@1419301443
# GitHub's other claims for a job: $1 repository, $2 workflow file, $3 ref, $4 event
claims() {
    printf ',"repository":"assali-mohamed-ali/%s","workflow_ref":"assali-mohamed-ali/%s/.github/workflows/%s@%s","ref":"%s","event_name":"%s"' \
        "$1" "$1" "$2" "$3" "$3" "$4"
}
API_CD_STAGING="$(claims api-MedLink cd.yml refs/heads/staging push)"
API_CD_TAG="$(claims api-MedLink cd.yml refs/tags/v1.2.0 push)"
FRONT_CD_STAGING="$(claims medlink-dashboard container-security.yml refs/heads/staging push)"
FRONT_CD_PROD="$(claims medlink-dashboard container-security.yml refs/heads/prod push)"

# 3. What each environment may do
check_role() { # $1 role $2 sub $3 claims $4 own path, then the paths it must not read
    local role="$1" sub="$2" more="$3" own="$4"; shift 4
    local token
    if ! token="$(login "$role" "$(jwt "$sub" "$AUD" 300 "$more")")"; then fail "$role: login refused for $sub"; return; fi
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
    local ttl; ttl="$(in_vault -e VAULT_TOKEN="$token" "$NAME" vault token lookup -format=json 2> /dev/null |
        grep -oE '"ttl": *[0-9]+' | grep -oE '[0-9]+$' || echo 0)"
    [ "${ttl:-0}" -gt 0 ] && [ "${ttl:-0}" -le 600 ] && pass "$role: token lives at most 10 minutes" ||
        fail "$role: token ttl $ttl s, expected 1..600"
}
check_role api-staging      "$API:environment:staging"      "$API_CD_STAGING"   staging/api   prod/api staging/front prod/front
check_role api-production   "$API:environment:production"   "$API_CD_TAG"       prod/api      staging/api staging/front prod/front
check_role front-staging    "$FRONT:environment:staging"    "$FRONT_CD_STAGING" staging/front prod/front staging/api prod/api
check_role front-production "$FRONT:environment:production" "$FRONT_CD_PROD"    prod/front    staging/front staging/api prod/api

# 4. Logins that must be refused, and the other runs the CD workflows make
refused() { # $1 what, $2 role, $3 jwt
    if login "$2" "$3" > /dev/null; then fail "login accepted: $1"; else pass "login refused: $1"; fi
}
accepted() { # $1 what, $2 role, $3 jwt
    if login "$2" "$3" > /dev/null; then pass "login accepted: $1"; else fail "login refused: $1"; fi
}
refused "staging token on the production role"  api-production "$(jwt "$API:environment:staging" "$AUD" 300 "$API_CD_TAG")"
refused "production token on the staging role"  api-staging    "$(jwt "$API:environment:production" "$AUD" 300 "$API_CD_STAGING")"
refused "front token on the API role"           api-staging    "$(jwt "$FRONT:environment:staging" "$AUD" 300 "$FRONT_CD_STAGING")"
refused "another repository"                    api-staging    "$(jwt "repo:someone-else/api-MedLink:environment:staging" "$AUD" 300 "$API_CD_STAGING")"
refused "an immutable subject for another repository" api-staging \
    "$(jwt "repo:assali-mohamed-ali@1/other@2:environment:staging" "$AUD" 300 "$API_CD_STAGING")"
refused "a pull request run"                    api-staging    "$(jwt "$API:pull_request" "$AUD" 300 "$(claims api-MedLink cd.yml refs/pull/7/merge pull_request)")"
refused "a branch run without environment"      api-staging    "$(jwt "$API:ref:refs/heads/staging" "$AUD" 300 "$API_CD_STAGING")"
refused "another audience"                      api-staging    "$(jwt "$API:environment:staging" "sigstore" 300 "$API_CD_STAGING")"
refused "an expired token"                      api-staging    "$(jwt "$API:environment:staging" "$AUD" -120 "$API_CD_STAGING")"
refused "a token signed with another key" api-staging "$(
    openssl genrsa -out "$WORK/other.pem" 2048 2> /dev/null; cp "$WORK/key.pem" "$WORK/key.bak"
    cp "$WORK/other.pem" "$WORK/key.pem"; jwt "$API:environment:staging" "$AUD" 300 "$API_CD_STAGING"; cp "$WORK/key.bak" "$WORK/key.pem")"
# The environment alone is not enough (PR #130 review): only the CD workflow, from its own branches or tags, by a
# push or a manual run, gets the secrets, even when another workflow or a pull request enters the same environment
refused "another workflow of the repository in the staging environment" api-staging \
    "$(jwt "$API:environment:staging" "$AUD" 300 "$(claims api-MedLink ci.yml refs/heads/staging push)")"
refused "a pull request job that enters the staging environment" api-staging \
    "$(jwt "$API:environment:staging" "$AUD" 300 "$(claims api-MedLink cd.yml refs/pull/7/merge pull_request)")"
refused "a pull_request_target job on the staging branch" api-staging \
    "$(jwt "$API:environment:staging" "$AUD" 300 "$(claims api-MedLink cd.yml refs/heads/staging pull_request_target)")"
refused "the CD workflow from a feature branch" api-staging \
    "$(jwt "$API:environment:staging" "$AUD" 300 "$(claims api-MedLink cd.yml refs/heads/feat/x workflow_dispatch)")"
refused "production from master (only the staging run or a v* tag)" api-production \
    "$(jwt "$API:environment:production" "$AUD" 300 "$(claims api-MedLink cd.yml refs/heads/master workflow_dispatch)")"
refused "a token without the workflow claims" api-staging "$(jwt "$API:environment:staging" "$AUD" 300)"
refused "another dashboard workflow in production" front-production \
    "$(jwt "$FRONT:environment:production" "$AUD" 300 "$(claims medlink-dashboard ci.yml refs/heads/prod push)")"
accepted "a manual staging deploy from master" api-staging \
    "$(jwt "$API:environment:staging" "$AUD" 300 "$(claims api-MedLink cd.yml refs/heads/master workflow_dispatch)")"
# Automatic production deploys run from the verified staging run (deploy-prod-from-staging, PR #132)
accepted "the API production deploy from the verified staging run" api-production \
    "$(jwt "$API:environment:production" "$AUD" 300 "$(claims api-MedLink cd.yml refs/heads/staging push)")"
accepted "the dashboard production deploy from the verified staging run" front-production \
    "$(jwt "$FRONT:environment:production" "$AUD" 300 "$(claims medlink-dashboard container-security.yml refs/heads/staging push)")"
# GitHub now puts the repository ids in sub (immutable subject claims): the roles accept both forms.
accepted "an immutable subject on the staging API role" api-staging \
    "$(jwt "$API_IMMUTABLE:environment:staging" "$AUD" 300 "$API_CD_STAGING")"
accepted "an immutable subject on the production API role" api-production \
    "$(jwt "$API_IMMUTABLE:environment:production" "$AUD" 300 "$API_CD_TAG")"
accepted "an immutable subject on the staging dashboard role" front-staging \
    "$(jwt "$FRONT_IMMUTABLE:environment:staging" "$AUD" 300 "$FRONT_CD_STAGING")"
accepted "an immutable subject on the production dashboard role" front-production \
    "$(jwt "$FRONT_IMMUTABLE:environment:production" "$AUD" 300 "$FRONT_CD_PROD")"
accepted "a dashboard release tag in production" front-production \
    "$(jwt "$FRONT:environment:production" "$AUD" 300 "$(claims medlink-dashboard container-security.yml refs/tags/v1.0.0 push)")"

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

# 7. seed-env.sh: fills the API secrets once, valid formats, never overwrites; refuses an unknown environment.
#    seed-front.sh: same contract for the dashboard's SSH bundle.
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

# 7b. seed-front.sh: stores the dashboard SSH bundle once and never overwrites it
ssh-keygen -q -t ed25519 -N "" -C seed-front-test -f "$WORK/front-key"
printf 'front.example ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITestOnly\n' > "$WORK/front-known"
docker cp "$(host_path "$WORK/front-key")" "$NAME:/tmp/front-key" > /dev/null
docker cp "$(host_path "$WORK/front-known")" "$NAME:/tmp/front-known" > /dev/null
as_root kv metadata delete medlink/staging/front > /dev/null
front_seed() { in_vault -e VAULT_TOKEN="$SETUP_TOKEN" "$NAME" sh /medlink-vault/seed-front.sh "$@" < /dev/null; }
if front_seed staging /tmp/front-key /tmp/front-known > "$WORK/front-seed1.log" 2>&1; then
    pass "seed-front.sh staging"
else
    fail "seed-front.sh staging"; cat "$WORK/front-seed1.log" >&2
fi
as_root kv get -field=ssh_private_key_b64 medlink/staging/front | openssl base64 -d -A | grep -q 'BEGIN .*PRIVATE KEY' &&
    pass "seed-front stores the private key" || fail "seed-front private key missing or invalid"
as_root kv get -field=known_hosts_b64 medlink/staging/front | openssl base64 -d -A | grep -q 'front.example' &&
    pass "seed-front stores known_hosts" || fail "seed-front known_hosts missing or invalid"
grep -qE 'BEGIN .*PRIVATE KEY|front\.example' "$WORK/front-seed1.log" && fail "seed-front printed a value" ||
    pass "seed-front prints no value"
front_before="$(as_root kv get -field=ssh_private_key_b64 medlink/staging/front)"
if front_seed staging /tmp/front-key /tmp/front-known > "$WORK/front-seed2.log" 2>&1; then
    pass "seed-front.sh second run"
else
    fail "seed-front.sh second run"
fi
front_after="$(as_root kv get -field=ssh_private_key_b64 medlink/staging/front)"
[ "$front_before" = "$front_after" ] && pass "seed-front never overwrites a value" || fail "seed-front overwrote a value"
grep -q 'nothing missing' "$WORK/front-seed2.log" && pass "seed-front reports an unchanged path" || fail "seed-front rerun message"
if front_seed qa /tmp/front-key /tmp/front-known > /dev/null 2>&1; then
    fail "seed-front accepted environment qa"
else
    pass "seed-front refuses an unknown environment"
fi
if front_seed staging /tmp/missing-key /tmp/front-known > /dev/null 2>&1; then
    fail "seed-front accepted a missing key"
else
    pass "seed-front refuses a missing key"
fi

# 8. install-host.sh on a throwaway Ubuntu 26.04 (the vault host's release): files, owners, modes, a valid rotation,
#    and a second run that changes nothing; snapshot.sh refuses to run without its token
HOST="medlink-vault-host-test-$$"
docker run -d --name "$HOST" -v "$(host_path "$VAULT_DIR"):/medlink-vault:ro" ubuntu:26.04 sleep 600 > /dev/null
on_host() { docker exec "$HOST" sh -c "$1"; }
trap 'docker rm -f "$HOST" > /dev/null 2>&1 || true; cleanup' EXIT
on_host 'apt-get update -qq > /dev/null && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq logrotate > /dev/null && useradd --system vault && install -d -o vault -g vault -m 755 /etc/vault.d'
listing='find /var/log/vault /var/backups/vault /etc/logrotate.d/vault-audit /etc/cron.d/medlink-vault-snapshot /usr/local/sbin/medlink-vault-snapshot /etc/vault.d -maxdepth 0 -printf "%p %u:%g %m\n"'
if on_host 'sh /medlink-vault/install-host.sh' > "$WORK/host1.log" 2>&1; then pass "install-host.sh runs"; else
    fail "install-host.sh"; cat "$WORK/host1.log" >&2; fi
on_host "$listing" > "$WORK/host1.lst"
grep -q '^/var/log/vault vault:vault 750$' "$WORK/host1.lst" && pass "audit log directory vault:vault 750" || fail "audit dir: $(head -1 "$WORK/host1.lst")"
grep -q '^/var/backups/vault root:root 700$' "$WORK/host1.lst" && pass "snapshot directory root 700" || fail "snapshot directory owner or mode"
grep -q '^/etc/vault.d vault:vault 755$' "$WORK/host1.lst" && pass "Vault's config directory left as it was" || fail "/etc/vault.d changed"
on_host 'logrotate -d /etc/logrotate.d/vault-audit' > "$WORK/logrotate.log" 2>&1 && ! grep -qi 'error' "$WORK/logrotate.log" &&
    pass "audit log rotation is valid" || { fail "logrotate rejects the rotation file"; cat "$WORK/logrotate.log" >&2; }
on_host 'sh /medlink-vault/install-host.sh' > /dev/null 2>&1 || fail "install-host.sh second run"
on_host "$listing" > "$WORK/host2.lst"
diff -q "$WORK/host1.lst" "$WORK/host2.lst" > /dev/null && pass "install-host.sh second run changes nothing" || fail "second run changed the host"
if on_host 'medlink-vault-snapshot' > /dev/null 2>&1; then fail "snapshot ran without a token"; else pass "snapshot refuses to run without its token"; fi
on_host 'grep -q "docker kill --signal=HUP medlink-vault" /etc/logrotate.d/vault-audit' &&
    pass "audit rotation signals the Vault container" || fail "audit rotation does not signal the container"

# 9. Vault in a container (vault/docker): reachable from the host only, pinned, and its data survives a restart sealed
COMPOSE="$VAULT_DIR/docker/docker-compose.yml"
HCL="$VAULT_DIR/docker/vault.hcl"
if [ -f "$COMPOSE" ] && [ -f "$HCL" ]; then
    pass "vault/docker holds the compose file and the server configuration"
    grep -q 'image: hashicorp/vault:1\.21\.0$' "$COMPOSE" && pass "image pinned to Vault 1.21.0" || fail "image not pinned"
    grep -q "127\.0\.0\.1:8200:8200" "$COMPOSE" && pass "published on 127.0.0.1 only" || fail "port not bound to 127.0.0.1"
    grep -q 'restart: unless-stopped' "$COMPOSE" && pass "comes back after a reboot" || fail "no restart policy"
    grep -q 'tls_disable *= *1' "$HCL" && grep -q 'path *= *"/vault/file"' "$HCL" && pass "raft storage on the mounted volume" || fail "storage path"
    BOOT="medlink-vault-boot-test-$$"
    trap 'docker rm -f "$BOOT" "$HOST" > /dev/null 2>&1 || true; docker volume rm -f "$BOOT" > /dev/null 2>&1 || true; cleanup' EXIT
    boot() { docker run -d --name "$BOOT" --user vault --entrypoint vault -e VAULT_API_ADDR=http://127.0.0.1:8200 \
        -v "$BOOT:/vault/file" -v "$(host_path "$VAULT_DIR/docker"):/vault/config:ro" "$IMAGE" server -config=/vault/config/vault.hcl > /dev/null; }
    in_boot() { docker exec -i -e VAULT_ADDR=http://127.0.0.1:8200 "$BOOT" vault "$@"; }
    wait_api() { for _ in $(seq 1 30); do in_boot status > "$WORK/boot.status" 2>&1; [ $? -ne 1 ] && return 0; sleep 1; done; return 1; }
    boot
    wait_api || fail "the container's Vault did not answer"
    in_boot operator init -key-shares=1 -key-threshold=1 -format=json > "$WORK/boot.json" 2> /dev/null || fail "operator init"
    UNSEAL="$(sed -n '/unseal_keys_b64/,/]/p' "$WORK/boot.json" | sed -n 's/^ *"\(.*\)"$/\1/p' | head -1)"
    in_boot operator unseal "$UNSEAL" > /dev/null 2>&1 || fail "operator unseal"
    in_boot status 2> /dev/null | grep -q 'Sealed *false' && pass "the container initialises and unseals" || fail "still sealed after unseal"
    docker restart "$BOOT" > /dev/null
    wait_api || fail "the container's Vault did not come back"
    grep -q 'Initialized *true' "$WORK/boot.status" && pass "its data survives a restart" || fail "data lost on restart"
    grep -q 'Sealed *true' "$WORK/boot.status" && pass "it comes back sealed" || fail "not sealed after a restart"
else
    fail "vault/docker/docker-compose.yml or vault.hcl is missing"
fi

echo
if [ "$FAILED" -eq 0 ]; then echo "vault configuration: all checks passed"; else echo "vault configuration: $FAILED check(s) failed"; exit 1; fi
