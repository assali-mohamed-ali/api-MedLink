#!/usr/bin/env bash
# GitHub #64 (MLink-164), offline: docker-compose.yml (the full stack, also what a server runs) has no fallback
# database credentials. With POSTGRES_DB, POSTGRES_USER or POSTGRES_PASSWORD missing or empty, `docker compose`
# refuses the file and names the variable; with all of them set, it accepts it. Only `docker compose config`:
# no daemon, no container, no network.
set -uo pipefail

COMPOSE_FILE="$(cd "$(dirname "$0")/.." && pwd)/docker-compose.yml"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
# An empty env file: compose would otherwise read the developer's .env next to the compose file
: > "$WORK/empty.env"

DB_VARIABLES="POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD"

# Every other variable the file already requires (${NAME:?...}) gets a dummy value, so that a refusal can only
# come from the database variables. Test values only, never real ones.
others=()
for name in $(grep -o '\${[A-Za-z_][A-Za-z0-9_]*:?' "$COMPOSE_FILE" | sed 's/^\${//; s/:?$//' | sort -u); do
    case " $DB_VARIABLES " in
        *" $name "*) ;;
        *) others+=("$name=test-only-value") ;;
    esac
done

failures=0
cases=0

# $1 name, $2 expected: "accept", or the variable compose must name when it refuses; then VAR=value pairs
run() {
    local name="$1" expected="$2"
    shift 2
    local out status
    out="$(env -u POSTGRES_DB -u POSTGRES_USER -u POSTGRES_PASSWORD "${others[@]}" "$@" \
        docker compose --env-file "$WORK/empty.env" -f "$COMPOSE_FILE" config --quiet 2>&1)"
    status=$?
    cases=$((cases + 1))
    if [ "$expected" = accept ]; then
        if [ "$status" != 0 ]; then
            echo "FAIL: $name: expected compose to accept the file, got exit $status: $out" >&2
            failures=$((failures + 1))
        fi
    elif [ "$status" = 0 ]; then
        echo "FAIL: $name: compose accepted the file without $expected" >&2
        failures=$((failures + 1))
    elif ! grep -q "$expected" <<<"$out"; then
        echo "FAIL: $name: compose refused the file, but its message does not name $expected: $out" >&2
        failures=$((failures + 1))
    fi
}

ALL_SET=(POSTGRES_DB=medlink POSTGRES_USER=medlink_owner POSTGRES_PASSWORD=test-only-password)

run 'all database variables set' accept "${ALL_SET[@]}"

for missing in $DB_VARIABLES; do
    set_others=()
    for pair in "${ALL_SET[@]}"; do
        [ "${pair%%=*}" = "$missing" ] || set_others+=("$pair")
    done
    run "$missing missing" "$missing" "${set_others[@]}"
    run "$missing empty" "$missing" "${set_others[@]}" "$missing="
done

if [ "$failures" -gt 0 ]; then
    echo "$failures of $cases cases failed" >&2
    exit 1
fi
echo "compose credentials: $cases cases passed"
