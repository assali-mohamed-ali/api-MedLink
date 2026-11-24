#!/usr/bin/env bash
# Tests deploy/update-app-image.sh (MLink-169): when Vault is unavailable, an image-only deploy updates APP_IMAGE in
# the server's existing .env and leaves every secret line untouched.
# Usage: bash deploy/test/update-app-image.test.sh
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPOSITORY="$(cd -- "$HERE/../.." && pwd -P)"
SCRIPT="$REPOSITORY/deploy/update-app-image.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

FAILED=0
fail() { echo "FAIL: $*" >&2; FAILED=$((FAILED + 1)); }
pass() { echo "ok:   $*"; }

mkdir -p "$WORK/api"
cat > "$WORK/api/.env" <<'EOF'
SPRING_PROFILES_ACTIVE=staging
APP_IMAGE='ghcr.io/assali-mohamed-ali/api-medlink@sha256:old'
POSTGRES_PASSWORD='not printed'
APP_IMAGE_OLD='not replaced'
EOF

if sh "$SCRIPT" "$WORK/api" "ghcr.io/assali-mohamed-ali/api-medlink@sha256:new" > "$WORK/run.log" 2>&1; then
    pass "updates an existing APP_IMAGE"
else
    fail "update failed"; cat "$WORK/run.log" >&2
fi
grep -qx "APP_IMAGE='ghcr.io/assali-mohamed-ali/api-medlink@sha256:new'" "$WORK/api/.env" &&
    pass "the new immutable image is in APP_IMAGE" || fail "APP_IMAGE was not updated"
grep -qx "POSTGRES_PASSWORD='not printed'" "$WORK/api/.env" &&
    pass "the secret lines are unchanged" || fail "a secret line changed"
grep -qx "APP_IMAGE_OLD='not replaced'" "$WORK/api/.env" &&
    pass "a similarly named key is not touched" || fail "APP_IMAGE_OLD changed"
[ "$(grep -c '^APP_IMAGE=' "$WORK/api/.env")" = 1 ] &&
    pass "there is exactly one APP_IMAGE line" || fail "APP_IMAGE is duplicated"
[ "$(stat -c %a "$WORK/api/.env")" = 600 ] &&
    pass "the .env stays private" || fail "the .env mode changed"

cat > "$WORK/api/.env" <<'EOF'
SPRING_PROFILES_ACTIVE=staging
POSTGRES_PASSWORD='not printed'
EOF
if sh "$SCRIPT" "$WORK/api" "ghcr.io/assali-mohamed-ali/api-medlink@sha256:new" > /dev/null 2>&1; then
    grep -qx "APP_IMAGE='ghcr.io/assali-mohamed-ali/api-medlink@sha256:new'" "$WORK/api/.env" &&
        pass "appends APP_IMAGE when it is missing" || fail "APP_IMAGE was not appended"
else
    fail "update without APP_IMAGE failed"
fi

if sh "$SCRIPT" "$WORK/missing" "ghcr.io/assali-mohamed-ali/api-medlink@sha256:new" > /dev/null 2>&1; then
    fail "accepted a missing .env"
else
    pass "refuses a missing .env"
fi
: > "$WORK/api/.env"
if sh "$SCRIPT" "$WORK/api" "ghcr.io/assali-mohamed-ali/api-medlink@sha256:new" > /dev/null 2>&1; then
    fail "accepted an empty .env"
else
    pass "refuses an empty .env"
fi
if sh "$SCRIPT" "$WORK/api" "ghcr.io/assali-mohamed-ali/api-medlink@sha256:bad'image" > /dev/null 2>&1; then
    fail "accepted a quote in the image"
else
    pass "refuses a quote in the image"
fi
if sh "$SCRIPT" "$WORK/api" $'ghcr.io/assali-mohamed-ali/api-medlink@sha256:bad\nimage' > /dev/null 2>&1; then
    fail "accepted a line break in the image"
else
    pass "refuses a line break in the image"
fi

echo
if [ "$FAILED" -eq 0 ]; then
    echo "update-app-image: all checks passed"
else
    echo "update-app-image: $FAILED check(s) failed"
    exit 1
fi
