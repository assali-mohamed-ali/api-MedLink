#!/usr/bin/env bash
# Tests the target guard of check-lockout.sh, offline: the script resets Keycloak's brute-force state and locks
# an account on purpose, so it must refuse any non-local URL before its first request, unless the clearly named
# opt-in is set. A fake curl records every call instead of reaching the network.
set -uo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/check-lockout.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
CALLS="$WORK/curl-calls"

# The fake curl answers nothing: past the guard, the script stops at "no Keycloak admin token" (exit 1)
mkdir "$WORK/bin"
cat > "$WORK/bin/curl" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$CURL_CALLS"
EOF
chmod +x "$WORK/bin/curl"

failures=0
cases=0

# $1 name, $2 expected exit code, then VAR=value pairs for the script's environment
run() {
    local name="$1" expected="$2"
    shift 2
    : > "$CALLS"
    local out status
    out="$(env -u APP_URL -u KEYCLOAK_URL -u ALLOW_REMOTE_DEV_STACK PATH="$WORK/bin:$PATH" CURL_CALLS="$CALLS" \
        "$@" bash "$SCRIPT" 2>&1)"
    status=$?
    cases=$((cases + 1))
    if [ "$status" != "$expected" ]; then
        echo "FAIL: $name: expected exit $expected, got $status: $out" >&2
        failures=$((failures + 1))
        return
    fi
    if [ "$expected" = 2 ] && [ -s "$CALLS" ]; then
        echo "FAIL: $name: refused, but a request was sent first: $(head -n 1 "$CALLS")" >&2
        failures=$((failures + 1))
    fi
    if [ "$expected" = 1 ] && [ ! -s "$CALLS" ]; then
        echo "FAIL: $name: should have passed the guard and called Keycloak" >&2
        failures=$((failures + 1))
    fi
}

# Local targets pass the guard (exit 1 = the fake Keycloak gave no token)
run 'defaults (localhost)' 1
run '127.0.0.1' 1 APP_URL=http://127.0.0.1:8080 KEYCLOAK_URL=http://127.0.0.1:8180
run 'IPv6 loopback' 1 APP_URL='http://[::1]:8080' KEYCLOAK_URL='http://[::1]:8180'
run '*.localhost' 1 KEYCLOAK_URL=http://keycloak.localhost:8180
run 'upper-case LOCALHOST' 1 KEYCLOAK_URL=http://LOCALHOST:8180

# Anything else is refused before the first request (exit 2)
run 'remote Keycloak' 2 KEYCLOAK_URL=https://keycloak.staging.medlink.example.com
run 'remote API' 2 APP_URL=https://staging.medlink.example.com
run 'lookalike localhost.evil.com' 2 KEYCLOAK_URL=http://localhost.evil.com:8180
run 'userinfo localhost@evil.com' 2 KEYCLOAK_URL=http://localhost@evil.com
run 'userinfo with a port, localhost:8180@evil.com' 2 KEYCLOAK_URL=http://localhost:8180@evil.com
run 'lookalike 127.0.0.1.nip.io' 2 KEYCLOAK_URL=http://127.0.0.1.nip.io:8180
run 'private address' 2 KEYCLOAK_URL=http://10.0.0.5:8180
run 'opt-in with another value' 2 KEYCLOAK_URL=http://dev-box.lan:8180 ALLOW_REMOTE_DEV_STACK=1

# The explicit opt-in lets a remote dev stack through
run 'remote dev stack, opt-in' 1 KEYCLOAK_URL=http://dev-box.lan:8180 APP_URL=http://dev-box.lan:8080 \
    ALLOW_REMOTE_DEV_STACK=yes-this-is-a-dev-stack

if [ "$failures" -ne 0 ]; then
    echo "$failures of $cases check-lockout guard test(s) failed" >&2
    exit 1
fi
echo "check-lockout guard tests passed ($cases cases)"
