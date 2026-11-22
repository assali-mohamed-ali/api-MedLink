#!/usr/bin/env bash
# MLink-68 (story MLink-19, anti-brute-force): proves on a running dev stack that 5 wrong passwords lock the
# account, so that the right password is refused next. Also the live demo of the story.
#
#   scripts/check-lockout.sh        (after scripts/setup-dev.sh; macOS, Linux, Git Bash on Windows)
#
# A locked account answers exactly like a wrong password (401, same message), so that nobody learns which
# accounts exist: the 401 to the right password is the proof, and Keycloak's brute-force status (admin API)
# confirms it is a lock. The script unlocks patient.dev at the end, even when the check fails, and fails (exit 1,
# never PASS) when it cannot unlock it.
# Prints PASS or FAIL, exits 0 or 1. Dev only: it uses the bootstrap admin of the dev Keycloak.
#
# It refuses (exit 2, before any request) a non-local APP_URL or KEYCLOAK_URL: pointed at staging or prod by
# mistake, it would lock or unlock a real account. For a dev stack on another machine, opt in explicitly:
#   ALLOW_REMOTE_DEV_STACK=yes-this-is-a-dev-stack APP_URL=http://dev-box:8080 KEYCLOAK_URL=http://dev-box:8180 \
#       scripts/check-lockout.sh
# Tests of this guard (offline): scripts/check-lockout.test.sh.
set -uo pipefail

APP_URL="${APP_URL:-http://localhost:8080}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"

# The host of a URL, lower case: no scheme, path, user info or port ("http://a@B:1/x" -> "b", "[::1]" kept)
url_host() {
    local rest="${1#*://}"
    # The authority ends at the first / ? # or \ (curl and browsers): cut there first, then drop the user-info
    rest="${rest%%[/?#\\]*}"
    rest="${rest##*@}"
    case "$rest" in
        \[*) rest="${rest%%]*}]" ;;
        *) rest="${rest%%:*}" ;;
    esac
    printf '%s' "$rest" | tr '[:upper:]' '[:lower:]'
}
is_local_host() {
    case "$1" in
        localhost | 127.0.0.1 | '[::1]' | *.localhost) return 0 ;;
        *) return 1 ;;
    esac
}
if [ "${ALLOW_REMOTE_DEV_STACK:-}" = yes-this-is-a-dev-stack ]; then
    echo "WARNING: ALLOW_REMOTE_DEV_STACK is set: running against $APP_URL and $KEYCLOAK_URL"
else
    for url in "$APP_URL" "$KEYCLOAK_URL"; do
        if ! is_local_host "$(url_host "$url")"; then
            echo "REFUSED: $url is not a local dev stack. This script locks and unlocks an account; for a remote" \
                "dev stack set ALLOW_REMOTE_DEV_STACK=yes-this-is-a-dev-stack." >&2
            exit 2
        fi
    done
fi
EMAIL='patient.dev@medlink.local'
WRONG='Not-The-Password-1!'
FAILURES=5   # realm failureFactor
# Seconds between two logins: Keycloak's quick-login guard locks after two failures less than 1 s apart (tests: 0)
PAUSE="${PAUSE_SECONDS:-1.2}"

# The dev stack's fixed values (compose.yaml, scripts/setup-dev.sh); for the full stack, pass the .env ones
KC_ADMIN_USER="${KC_ADMIN_USER:-admin}"
KC_ADMIN_PASS="${KC_ADMIN_PASS:-admin}"
USERS_PASSWORD="${USERS_PASSWORD:-MedLink-Dev-2026!}"

admin_token() {
    curl -s --max-time 15 -d grant_type=password -d client_id=admin-cli \
        --data-urlencode "username=$KC_ADMIN_USER" --data-urlencode "password=$KC_ADMIN_PASS" \
        "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" |
        sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}
TOKEN="$(admin_token)"
[ -n "$TOKEN" ] || { echo "FAIL: no Keycloak admin token from $KEYCLOAK_URL (is the dev stack up?)"; exit 1; }
USER_ID="$(curl -s --max-time 15 -H "Authorization: Bearer $TOKEN" \
    "$KEYCLOAK_URL/admin/realms/medlink/users?email=$EMAIL&exact=true" | sed -n 's/^\[{"id":"\([^"]*\)".*/\1/p')"
[ -n "$USER_ID" ] || { echo "FAIL: $EMAIL not found in the medlink realm"; exit 1; }
BRUTE_FORCE="$KEYCLOAK_URL/admin/realms/medlink/attack-detection/brute-force/users/$USER_ID"

# Clears patient.dev's failures (Keycloak answers 204). Fails, with the reason on stderr, when there is no admin token
# or Keycloak answers anything else: a silent failure would leave the dev account locked for 15 minutes.
unlock() {
    local token status
    token="$(admin_token)"   # a fresh token: the first one may have expired during the run
    if [ -z "$token" ]; then
        echo "FAIL: could not unlock $EMAIL: no Keycloak admin token" >&2
        return 1
    fi
    status="$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 -X DELETE         -H "Authorization: Bearer $token" "$BRUTE_FORCE")"
    if [ "$status" != 204 ]; then
        echo "FAIL: could not unlock $EMAIL: Keycloak answered HTTP ${status:-none} (expected 204)" >&2
        return 1
    fi
}
COOKIES="$(mktemp)"
UNLOCKED=no
# On any exit, even an interrupted or failed run: unlock patient.dev (unless the end of the run did) and remove the
# cookie jar. A failed unlock turns the exit status into 1, whatever came before.
cleanup() {
    local status=$?
    rm -f "$COOKIES"
    if [ "$UNLOCKED" != yes ] && ! unlock; then
        echo "  unlock it by hand: Keycloak admin console > medlink > Users > $EMAIL, or wait 15 minutes" >&2
        status=1
    fi
    exit "$status"
}
trap cleanup EXIT
# Start from zero failures, whatever an earlier run left; without that, the count below proves nothing
unlock || exit 1

curl -s -o /dev/null --max-time 10 -c "$COOKIES" -b "$COOKIES" "$APP_URL/api/auth/csrf"
XSRF="$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$COOKIES" | head -n 1)"
login() { # $1 = password -> HTTP status of POST /api/auth/login
    curl -s -o /dev/null -w '%{http_code}' --max-time 30 -c "$COOKIES" -b "$COOKIES" \
        -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
        -d "{\"email\":\"$EMAIL\",\"password\":\"$1\"}" "$APP_URL/api/auth/login"
}

RESULT=PASS
i=1
while [ "$i" -le "$FAILURES" ]; do
    # Keycloak's quick-login guard locks for 1 minute after two failures less than 1 s apart: wait, so the
    # lock below comes from the 5 failures, not from speed
    sleep "$PAUSE"
    STATUS="$(login "$WRONG")"
    echo "  wrong password $i/$FAILURES -> $STATUS"
    [ "$STATUS" = 401 ] || RESULT=FAIL
    i=$((i + 1))
done

sleep "$PAUSE"
STATUS="$(login "$USERS_PASSWORD")"
echo "  right password      -> $STATUS (expected 401: locked)"
[ "$STATUS" = 401 ] || RESULT=FAIL

LOCKED="$(curl -s --max-time 15 -H "Authorization: Bearer $(admin_token)" "$BRUTE_FORCE" |
    sed -n 's/.*"disabled":\([a-z]*\).*/\1/p')"
echo "  Keycloak brute-force status: disabled=${LOCKED:-?} (expected true)"
[ "$LOCKED" = true ] || RESULT=FAIL

# Unlock before printing the result: a run that leaves the account locked is not a PASS
if unlock; then
    UNLOCKED=yes
else
    RESULT=FAIL
    echo "  unlock it by hand: Keycloak admin console > medlink > Users > $EMAIL, or wait 15 minutes" >&2
fi

echo "$RESULT"
[ "$RESULT" = PASS ]
