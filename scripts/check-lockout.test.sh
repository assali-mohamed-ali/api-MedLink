#!/usr/bin/env bash
# Tests check-lockout.sh offline: its target guard and its unlock. The guard: the script resets Keycloak's brute-force state and locks
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

# ---------------------------------------------------------------------------------------------
# The unlock (review of #103): a failed unlock must never end in PASS, and must exit non-zero, so patient.dev is not
# left locked unnoticed. A fake dev stack answers like Keycloak and the API; FAKE_UNLOCK_CODES lists the HTTP status
# of each unlock (DELETE brute-force) in turn, the last one repeating; FAKE_TOKEN_AFTER=n gives no admin token from
# the n-th token request on.
mkdir "$WORK/stack"
cat > "$WORK/stack/curl" <<'EOF2'
#!/usr/bin/env bash
url="" method=GET format=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        -X) method="$2"; shift ;;
        -w) format="$2"; shift ;;
        -o | -c | -b | -d | -H | --max-time | --data-urlencode) shift ;;
        http*) url="$1" ;;
    esac
    shift
done
count() { # $1 = counter name -> its new value
    local f="$FAKE_DIR/$1"; local n=$(( $(cat "$f" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$f"; echo "$n"
}
case "$method $url" in
    *openid-connect/token)
        n="$(count token)"
        if [ -z "${FAKE_TOKEN_AFTER:-}" ] || [ "$n" -lt "$FAKE_TOKEN_AFTER" ]; then echo '{"access_token":"t"}'; fi ;;
    *users\?email=*) echo '[{"id":"u1","username":"patient.dev"}]' ;;
    "DELETE "*brute-force*)
        n="$(count unlock)"; codes=( ${FAKE_UNLOCK_CODES:-204} ); i=$(( n - 1 ))
        [ "$i" -lt "${#codes[@]}" ] || i=$(( ${#codes[@]} - 1 ))
        echo "${codes[$i]}" >> "$FAKE_DIR/unlocks"
        [ -n "$format" ] && printf '%s' "${codes[$i]}" ;;
    "GET "*brute-force*) echo '{"numFailures":5,"disabled":true}' ;;
    *api/auth/login) [ -n "$format" ] && printf '401' ;;
esac
exit 0
EOF2
chmod +x "$WORK/stack/curl"

# $1 name, $2 expected exit code, $3 text the output must hold, $4 text it must not hold (or ''), then VAR=value pairs
flow() {
    local name="$1" expected="$2" must="$3" mustnot="$4"
    shift 4
    rm -rf "$WORK/fake" && mkdir "$WORK/fake"
    local out status
    out="$(env -u APP_URL -u KEYCLOAK_URL -u ALLOW_REMOTE_DEV_STACK PATH="$WORK/stack:$PATH" FAKE_DIR="$WORK/fake"         PAUSE_SECONDS=0 "$@" bash "$SCRIPT" 2>&1)"
    status=$?
    cases=$((cases + 1))
    if [ "$status" != "$expected" ] || ! grep -q -- "$must" <<<"$out" || { [ -n "$mustnot" ] && grep -q -- "$mustnot" <<<"$out"; }; then
        echo "FAIL: $name: expected exit $expected with \"$must\"${mustnot:+ and no \"$mustnot\"}, got $status:" >&2
        sed 's/^/    /' <<<"$out" >&2
        failures=$((failures + 1))
    fi
}

flow 'lock proven, unlocked at the end' 0 '^PASS' '' FAKE_UNLOCK_CODES='204'
flow 'final unlock refused (HTTP 500)' 1 'could not unlock' '^PASS' FAKE_UNLOCK_CODES='204 500'
flow 'final unlock with an expired admin session (HTTP 401)' 1 'could not unlock' '^PASS' FAKE_UNLOCK_CODES='204 401'
flow 'no admin token for the final unlock' 1 'could not unlock' '^PASS' FAKE_TOKEN_AFTER=3
flow 'first unlock refused: the run does not start' 1 'could not unlock' 'wrong password 1/5' FAKE_UNLOCK_CODES='500'

if [ "$failures" -ne 0 ]; then
    echo "$failures of $cases check-lockout test(s) failed" >&2
    exit 1
fi
echo "check-lockout tests passed ($cases cases)"
