#!/usr/bin/env bash
#
# Start and verify the api-MedLink dev stack on macOS and Linux (Keycloak + Postgres + Spring Boot BFF).
# Windows: use scripts/setup-dev.ps1, which does the same steps with the same options.
#
# Works on the clone this script lives in. The only things to install are Git and Docker Desktop.
#
#   1. Checks prerequisites: Docker (running), curl, a JDK 17+ (found automatically).
#      Without a JDK 17+ it switches to --full-stack, where Docker builds and runs the API.
#   2. Checks the repository layout.
#   3. Creates .env from .env.example with generated secrets (only if .env is missing).
#   4. Starts the containers and waits until Keycloak serves the "medlink" realm. The realm is
#      re-imported automatically when keycloak/realm-medlink.json changed since the last run.
#      Before any wipe, the current realm is saved to .dev/realm-backups/ (no users, no secrets).
#   5. Verifies the imported realm through the Keycloak admin API (roles, groups, client, secret, users).
#   6. Runs ./mvnw verify (unless --skip-tests).
#   7. Starts the API and runs smoke tests, including a real login as patient.dev through POST /api/auth/login.
#
# Usage: ./scripts/setup-dev.sh [--skip-tests] [--full-stack] [--reset] [--keep-data] [--stop]
#
#   --skip-tests  Skip ./mvnw verify (faster).
#   --full-stack  Run everything in containers with docker-compose.yml (app image + Prometheus)
#                 instead of compose.yaml + "mvnw spring-boot:run".
#   --reset       Delete the Docker volumes first (wipes Keycloak and app data, forces a fresh realm import).
#   --keep-data   Never delete the Docker volumes, even when the realm file changed (the realm may then be stale).
#   --stop        Stop the app and the containers, then exit.
#
# Re-running is safe: it restarts the app and re-checks everything.
# Written for bash 3.2, the version macOS ships.

set -uo pipefail

SKIP_TESTS=0
FULL_STACK=0
RESET=0
KEEP_DATA=0
STOP=0
for arg in "$@"; do
    case "$arg" in
        --skip-tests) SKIP_TESTS=1 ;;
        --full-stack) FULL_STACK=1 ;;
        --reset) RESET=1 ;;
        --keep-data) KEEP_DATA=1 ;;
        --stop) STOP=1 ;;
        -h | --help) sed -n '3,27p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown option: $arg (see --help)" >&2; exit 2 ;;
    esac
done

REPO="$(cd "$(dirname "$0")/.." && pwd)"
TMP_ROOT="${TMPDIR:-/tmp}"
LOG_DIR="${TMP_ROOT%/}/medlink-setup"
PID_FILE="$LOG_DIR/app.pid"
# Which realm file and compose mode the Keycloak volume was created from (git-ignored).
REALM_STAMP="$REPO/.dev/realm.stamp"
# Realm exports taken automatically before the dev volumes are deleted (git-ignored).
BACKUP_DIR="$REPO/.dev/realm-backups"
KEYCLOAK_URL='http://localhost:8180'
APP_URL='http://localhost:8080'
DEV_USERS_PASSWORD='MedLink-Dev-2026!' # matches compose.yaml and the docs
mkdir -p "$LOG_DIR"

FAILURES=0
WARNINGS=0

if [ -t 1 ]; then
    C_STEP=$'\033[36m'; C_OK=$'\033[32m'; C_WARN=$'\033[33m'; C_BAD=$'\033[31m'; C_OFF=$'\033[0m'
else
    C_STEP=''; C_OK=''; C_WARN=''; C_BAD=''; C_OFF=''
fi

step() { printf '\n%s==> %s%s\n' "$C_STEP" "$1" "$C_OFF"; }
ok() { printf '  %s[OK]%s   %s\n' "$C_OK" "$C_OFF" "$1"; }
info() { printf '  %s[INFO]%s %s\n' "$C_WARN" "$C_OFF" "$1"; }
warn() { WARNINGS=$((WARNINGS + 1)); printf '  %s[WARN]%s %s\n' "$C_WARN" "$C_OFF" "$1"; }
bad() { FAILURES=$((FAILURES + 1)); printf '  %s[FAIL]%s %s\n' "$C_BAD" "$C_OFF" "$1"; }
abort() { printf '\n%s[ABORT]%s %s\n' "$C_BAD" "$C_OFF" "$1"; exit 1; }

http_status() { curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$1"; }

# wait_until <seconds> <what> <command...>
wait_until() {
    local timeout="$1" what="$2" waited=0
    shift 2
    printf '  waiting for %s (up to %s s)' "$what" "$timeout"
    while [ "$waited" -lt "$timeout" ]; do
        if "$@"; then echo ' ready'; return 0; fi
        printf '.'
        sleep 5
        waited=$((waited + 5))
    done
    echo ' timed out'
    return 1
}

realm_ready() { [ "$(http_status "$KEYCLOAK_URL/realms/medlink/.well-known/openid-configuration")" = 200 ]; }
app_ready() { [ "$(http_status "$APP_URL/actuator/health")" = 200 ]; }

# True when something already listens on the port.
port_busy() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

kill_tree() {
    local child
    for child in $(pgrep -P "$1" 2>/dev/null); do kill_tree "$child"; done
    kill "$1" 2>/dev/null
}

stop_app() {
    local old_pid
    if [ -f "$PID_FILE" ]; then
        old_pid="$(cat "$PID_FILE")"
        if kill -0 "$old_pid" 2>/dev/null; then
            kill_tree "$old_pid"
            ok "stopped the previous app run (PID $old_pid)"
        fi
        rm -f "$PID_FILE"
    fi
}

# stop_containers [volumes]
stop_containers() {
    local file
    for file in compose.yaml docker-compose.yml; do
        # docker-compose.yml needs its required variables even for "down": .env provides them.
        if [ "${1:-}" = volumes ]; then
            (cd "$REPO" && docker compose -f "$file" down -v >/dev/null 2>&1)
        else
            (cd "$REPO" && docker compose -f "$file" down >/dev/null 2>&1)
        fi
    done
}

read_env() { sed -n "s/^$1=//p" "$REPO/.env" | head -n 1 | tr -d '\r'; }

new_secret() { LC_ALL=C od -An -N24 -tx1 /dev/urandom | tr -d ' \n'; }

file_hash() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# The realm file is only imported into an empty Keycloak, so the volume is tied to the file it came from.
realm_stamp() { echo "$1 $(file_hash "$REPO/keycloak/realm-medlink.json")"; }

# Before the dev volumes are deleted: save the realm as it is in Keycloak (flows, roles, groups,
# clients; no users, secrets masked), so console work that was never exported is not lost.
# Best effort: a failure here is a warning, it never blocks the run.
backup_realm() {
    local previous_file="$1" project user pass token file
    project="$(docker compose -f compose.yaml config 2>/dev/null | sed -n 's/^name: //p' | head -n 1)"
    [ -n "$project" ] || return 0
    docker volume inspect "${project}_keycloak-db-data" >/dev/null 2>&1 || return 0 # nothing to save yet

    if ! realm_ready; then
        docker compose -f "$previous_file" up -d keycloak >/dev/null 2>&1
        if ! wait_until 180 'Keycloak, to save the realm before the wipe' realm_ready; then
            warn 'the realm could not be saved before the wipe: Keycloak did not start'
            return 0
        fi
    fi

    user='admin'
    pass='admin' # fixed dev values in compose.yaml
    if [ "$previous_file" = docker-compose.yml ] && [ -f .env ]; then
        user="$(read_env KEYCLOAK_ADMIN)"
        user="${user:-admin}"
        pass="$(read_env KEYCLOAK_ADMIN_PASSWORD)"
    fi
    token="$(curl -s --max-time 15 \
        -d grant_type=password -d client_id=admin-cli \
        --data-urlencode "username=$user" --data-urlencode "password=$pass" \
        "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" |
        sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"

    mkdir -p "$BACKUP_DIR"
    file="$BACKUP_DIR/realm-backup-$(date +%Y%m%d-%H%M%S).json"
    if [ -n "$token" ] && curl -s -f --max-time 60 -X POST -H "Authorization: Bearer $token" -o "$file" \
        "$KEYCLOAK_URL/admin/realms/medlink/partial-export?exportClients=true&exportGroupsAndRoles=true" &&
        grep -q '"realm"' "$file"; then
        ok "realm saved to $file before the wipe"
    else
        rm -f "$file"
        warn 'the realm could not be saved before the wipe: admin login or export refused'
    fi
    # keep the 10 most recent backups (names sort by date)
    find "$BACKUP_DIR" -name 'realm-backup-*.json' | sort -r | tail -n +11 | while IFS= read -r old; do rm -f "$old"; done
}

# Major version of a JDK folder, read from its "release" file (0 when the folder is not a JDK).
jdk_major() {
    local jdk_home="$1" major
    if [ -z "$jdk_home" ] || [ ! -x "$jdk_home/bin/javac" ] || [ ! -f "$jdk_home/release" ]; then echo 0; return; fi
    major="$(sed -n 's/^JAVA_VERSION="\([0-9][0-9]*\).*/\1/p' "$jdk_home/release" | head -n 1)"
    echo "${major:-0}"
}

# A JDK 17+ on this machine: JAVA_HOME when it is recent enough, else the usual install folders
# (17 first, then the oldest newer one). Prints nothing when there is none.
find_jdk() {
    local candidate major best='' best_major=0 java_bin
    if [ "$(jdk_major "${JAVA_HOME:-}")" -ge 17 ]; then echo "$JAVA_HOME"; return; fi
    java_bin="$(command -v java 2>/dev/null || true)"
    # /usr/bin/java on macOS is a stub that opens an "install Java" dialog when no JDK exists: never run it.
    if [ -n "$java_bin" ] && [ "$java_bin" != /usr/bin/java ]; then
        candidate="$("$java_bin" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.home = //p' | head -n 1)"
    else
        candidate=''
    fi
    for candidate in \
        "$candidate" \
        "$([ -x /usr/libexec/java_home ] && /usr/libexec/java_home -v 17 2>/dev/null)" \
        /Library/Java/JavaVirtualMachines/*/Contents/Home \
        "$HOME"/Library/Java/JavaVirtualMachines/*/Contents/Home \
        /opt/homebrew/opt/openjdk*/libexec/openjdk.jdk/Contents/Home \
        /usr/local/opt/openjdk*/libexec/openjdk.jdk/Contents/Home \
        "$HOME"/.sdkman/candidates/java/* \
        "$HOME"/.asdf/installs/java/* \
        "$HOME"/.jdks/* \
        /usr/lib/jvm/*; do
        major="$(jdk_major "$candidate")"
        if [ "$major" -ge 17 ] && { [ "$best_major" -eq 0 ] || [ "$major" -lt "$best_major" ]; }; then
            best="$candidate"
            best_major="$major"
        fi
    done
    echo "$best"
}

# ---------------------------------------------------------------------------------------------
if [ "$STOP" -eq 1 ]; then
    step 'Stopping api-MedLink'
    stop_app
    stop_containers
    ok 'containers stopped (volumes kept)'
    exit 0
fi

# ---------------------------------------------------------------------------------------------
step '1/7 Checking prerequisites'

for tool in docker curl; do
    command -v "$tool" >/dev/null 2>&1 || abort "$tool is not installed or not on PATH."
done
ok 'docker and curl found'

docker info >/dev/null 2>&1 || abort 'Docker is installed but not running. Start Docker Desktop, wait until it says "Engine running", then re-run.'
docker compose version >/dev/null 2>&1 || abort 'docker compose v2 is missing. Update Docker Desktop (or install the compose plugin).'
ok 'Docker is running (compose v2 available)'

if [ "$FULL_STACK" -eq 0 ]; then
    JDK="$(find_jdk)"
    if [ -n "$JDK" ]; then
        # Only for this run and the processes it starts: nothing is changed on the machine.
        export JAVA_HOME="$JDK"
        export PATH="$JDK/bin:$PATH"
        ok "JDK $(jdk_major "$JDK") at $JDK"
    else
        FULL_STACK=1
        info 'no JDK 17+ found: switching to --full-stack, Docker builds and runs the API'
    fi
fi

# ---------------------------------------------------------------------------------------------
step '2/7 Checking the repository'

cd "$REPO" || abort "cannot enter $REPO"
for required in compose.yaml docker-compose.yml keycloak/realm-medlink.json mvnw .env.example; do
    [ -f "$required" ] || abort "$required is missing in $REPO. Pull the latest master."
done
ok "repository layout looks right ($REPO)"

# ---------------------------------------------------------------------------------------------
step '3/7 Preparing .env'

if [ -f .env ]; then
    ok '.env already exists, keeping it'
else
    tr -d '\r' < .env.example | awk \
        -v pg="$(new_secret)" -v kcdb="$(new_secret)" -v kcadmin="$(new_secret)" \
        -v bff="$(new_secret)" -v kcapi="$(new_secret)" -v users="$DEV_USERS_PASSWORD" '
        /^POSTGRES_PASSWORD=/       { print "POSTGRES_PASSWORD=" pg; next }
        /^KEYCLOAK_DB_PASSWORD=/    { print "KEYCLOAK_DB_PASSWORD=" kcdb; next }
        /^KEYCLOAK_ADMIN_PASSWORD=/ { print "KEYCLOAK_ADMIN_PASSWORD=" kcadmin; next }
        /^KC_BFF_CLIENT_SECRET=/    { print "KC_BFF_CLIENT_SECRET=" bff; next }
        /^KC_ADMIN_CLIENT_SECRET=/  { print "KC_ADMIN_CLIENT_SECRET=" kcapi; next }
        /^KC_DEV_USERS_PASSWORD=/   { print "KC_DEV_USERS_PASSWORD=" users; next }
        { print }' > .env
    chmod 600 .env
    ok '.env created with random secrets (git-ignored, never commit it)'
fi
# Keys added to .env.example after this .env was created: appended, secrets generated.
ADDED=''
for key in $(tr -d '\r' < .env.example | sed -n 's/^\([A-Za-z_][A-Za-z0-9_]*\)=.*/\1/p'); do
    grep -q "^$key=" .env && continue
    case "$key" in
        *SECRET* | *PASSWORD*) value="$(new_secret)" ;;
        *) value="$(tr -d '\r' < .env.example | sed -n "s/^$key=//p" | head -n 1)" ;;
    esac
    printf '\n%s=%s' "$key" "$value" >> .env
    ADDED="$ADDED $key"
done
[ -n "$ADDED" ] && ok "added to .env:$ADDED"
if [ "$FULL_STACK" -eq 1 ]; then
    for key in KC_BFF_CLIENT_SECRET KC_ADMIN_CLIENT_SECRET KEYCLOAK_DB_PASSWORD KEYCLOAK_ADMIN_PASSWORD KC_DEV_USERS_PASSWORD; do
        value="$(read_env "$key")"
        case "$value" in
            '' | change-me*) abort "$key in .env is empty or still 'change-me'. Fill it in, or delete .env to regenerate it." ;;
        esac
    done
fi

# ---------------------------------------------------------------------------------------------
step '4/7 Starting containers'

if [ "$FULL_STACK" -eq 1 ]; then COMPOSE_FILE='docker-compose.yml'; else COMPOSE_FILE='compose.yaml'; fi
WANTED_STAMP="$(realm_stamp "$COMPOSE_FILE")"
KNOWN_STAMP=''
[ -f "$REALM_STAMP" ] && KNOWN_STAMP="$(tr -d '\r' < "$REALM_STAMP")"
if [ "$RESET" -eq 0 ] && [ "$KNOWN_STAMP" != "$WANTED_STAMP" ]; then
    if [ "$KEEP_DATA" -eq 1 ]; then
        warn 'the realm file or the compose mode changed since the volumes were created; kept because of --keep-data'
    else
        RESET=1
        info 'realm file or compose mode changed since the last run: re-importing the realm'
    fi
fi

if [ "$RESET" -eq 1 ]; then
    PREVIOUS_COMPOSE_FILE="${KNOWN_STAMP%% *}"
    backup_realm "${PREVIOUS_COMPOSE_FILE:-$COMPOSE_FILE}"
fi

stop_app
if [ "$RESET" -eq 1 ]; then
    stop_containers volumes
    ok 'volumes deleted: Keycloak will import the realm from scratch'
else
    stop_containers
fi

PORTS='8080 8180 1025 8025'   # API, Keycloak, Mailpit SMTP and inbox
if [ "$FULL_STACK" -eq 1 ]; then
    # Host side only: inside the compose network the API still reaches postgres:5432.
    PG_PORT=5432
    while port_busy "$PG_PORT" && [ "$PG_PORT" -lt 5442 ]; do PG_PORT=$((PG_PORT + 1)); done
    [ "$PG_PORT" -ne 5432 ] && info "port 5432 is taken (a local PostgreSQL?): the database is published on $PG_PORT instead"
    export POSTGRES_HOST_PORT="$PG_PORT"
    PORTS="$PORTS $PG_PORT 9090"
fi
BUSY=''
for port in $PORTS; do
    port_busy "$port" && BUSY="$BUSY
  port $port is already in use"
done
[ -n "$BUSY" ] && abort "Free these ports first:$BUSY"
ok "ports $PORTS are free"

if [ "$FULL_STACK" -eq 1 ]; then
    KC_ADMIN_USER="$(read_env KEYCLOAK_ADMIN)"
    KC_ADMIN_USER="${KC_ADMIN_USER:-admin}"
    KC_ADMIN_PASS="$(read_env KEYCLOAK_ADMIN_PASSWORD)"
    EXPECTED_SECRET="$(read_env KC_BFF_CLIENT_SECRET)"
    EXPECTED_ADMIN_SECRET="$(read_env KC_ADMIN_CLIENT_SECRET)"
    USERS_PASSWORD="$(read_env KC_DEV_USERS_PASSWORD)"
    docker compose -f "$COMPOSE_FILE" up -d --build || abort "docker compose -f $COMPOSE_FILE up failed."
else
    KC_ADMIN_USER='admin'
    KC_ADMIN_PASS='admin'                          # fixed dev values in compose.yaml
    EXPECTED_SECRET='dev-only-medlink-bff-secret'  # compose.yaml + application-dev.yml
    EXPECTED_ADMIN_SECRET='dev-only-medlink-admin-secret'  # compose.yaml + application-dev.yml
    USERS_PASSWORD="$DEV_USERS_PASSWORD"
    docker compose -f "$COMPOSE_FILE" up -d || abort "docker compose -f $COMPOSE_FILE up failed."
fi
ok "containers started from $COMPOSE_FILE"

if ! wait_until 420 'Keycloak and the medlink realm' realm_ready; then
    echo '  last Keycloak log lines:'
    docker compose -f "$COMPOSE_FILE" logs --tail 60 keycloak
    abort 'Keycloak did not serve the medlink realm. Check the log above (realm import errors show up there).'
fi
ok 'Keycloak is up and serves realm medlink'
mkdir -p "$(dirname "$REALM_STAMP")"
echo "$WANTED_STAMP" > "$REALM_STAMP"

# ---------------------------------------------------------------------------------------------
step '5/7 Verifying the imported realm (Keycloak admin API)'

TOKEN="$(curl -s --max-time 15 \
    -d grant_type=password -d client_id=admin-cli \
    --data-urlencode "username=$KC_ADMIN_USER" --data-urlencode "password=$KC_ADMIN_PASS" \
    "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" |
    sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"

admin_get() { curl -s --max-time 15 -H "Authorization: Bearer $TOKEN" "$KEYCLOAK_URL/admin/realms/medlink$1"; }
# has_field <json> <field> <value>
has_field() { printf '%s' "$1" | grep -q "\"$2\":\"$3\""; }
# missing_values <json> <field> <value...>: prints the values that are absent
missing_values() {
    local json="$1" field="$2" value missing=''
    shift 2
    for value in "$@"; do has_field "$json" "$field" "$value" || missing="$missing $value"; done
    echo "${missing# }"
}

if [ -z "$TOKEN" ]; then
    bad 'Keycloak admin API check failed: could not get an admin token'
    [ "$FULL_STACK" -eq 0 ] && echo '  (if Keycloak data comes from an older run with another admin password, re-run with --reset)'
else
    WELL_KNOWN="$(curl -s --max-time 15 "$KEYCLOAK_URL/realms/medlink/.well-known/openid-configuration")"
    if has_field "$WELL_KNOWN" issuer "$KEYCLOAK_URL/realms/medlink"; then ok "issuer = $KEYCLOAK_URL/realms/medlink"
    else bad "issuer is not $KEYCLOAK_URL/realms/medlink (check KC_HOSTNAME)"; fi

    MISSING="$(missing_values "$(admin_get /roles)" name ADMINISTRATIVE_STAFF PRACTITIONER PATIENT)"
    if [ -n "$MISSING" ]; then bad "missing realm roles: $MISSING"; else ok 'roles ADMINISTRATIVE_STAFF, PRACTITIONER, PATIENT'; fi

    MISSING="$(missing_values "$(admin_get /groups)" name administrative-staff practitioners patients)"
    if [ -n "$MISSING" ]; then bad "missing groups: $MISSING"; else ok 'groups administrative-staff, practitioners, patients'; fi

    CLIENT="$(admin_get '/clients?clientId=medlink-bff')"
    CLIENT_ID="$(printf '%s' "$CLIENT" | sed -n 's/^\[{"id":"\([^"]*\)".*/\1/p')"
    if [ -z "$CLIENT_ID" ]; then
        bad 'client medlink-bff not found'
    else
        ok 'client medlink-bff exists'
        REDIRECTS="$(printf '%s' "$CLIENT" | sed -n 's/.*"redirectUris":\[\([^]]*\)\].*/\1/p' | tr -d '"')"
        case "$REDIRECTS" in
            *'${'*) bad "redirect URIs contain an unresolved placeholder: $REDIRECTS" ;;
            *) ok "redirect URI $REDIRECTS" ;;
        esac
        if has_field "$CLIENT" 'pkce.code.challenge.method' S256; then ok 'PKCE S256 enforced'; else bad 'PKCE S256 not set on the client'; fi
        SECRET="$(admin_get "/clients/$CLIENT_ID/client-secret" | sed -n 's/.*"value":"\([^"]*\)".*/\1/p')"
        case "$SECRET" in
            "$EXPECTED_SECRET") ok 'client secret matches what the API uses' ;;
            *'${'*) bad 'client secret is an unresolved placeholder: KC_BFF_CLIENT_SECRET was not passed to Keycloak' ;;
            *) bad 'client secret differs from what the API uses (realm imported earlier with another secret? re-run with --reset)' ;;
        esac
    fi

    # Login protections from the realm file (MLink-66, MLink-67) and the login endpoint's grant (MLink-55).
    REALM_JSON="$(admin_get '')"
    if printf '%s' "$REALM_JSON" | grep -q '"bruteForceProtected":true' \
        && printf '%s' "$REALM_JSON" | grep -q '"failureFactor":5[,}]' \
        && printf '%s' "$REALM_JSON" | grep -q '"waitIncrementSeconds":900[,}]' \
        && printf '%s' "$REALM_JSON" | grep -q '"permanentLockout":false'; then ok 'lockout: 15 minutes after 5 failed logins'
    else bad 'lockout settings differ from 5 failures / 900 s / not permanent'; fi
    if printf '%s' "$REALM_JSON" | grep -q '"ssoSessionIdleTimeout":900[,}]' \
        && printf '%s' "$REALM_JSON" | grep -q '"ssoSessionMaxLifespan":36000[,}]'; then ok 'Keycloak sessions: 15 minutes idle, 10 hours max'
    else bad 'Keycloak sessions differ from 15 minutes idle / 10 hours max'; fi
    EVENTS_JSON="$(admin_get '/events/config')"
    if printf '%s' "$EVENTS_JSON" | grep -q '"eventsEnabled":true' \
        && printf '%s' "$EVENTS_JSON" | grep -q '"adminEventsEnabled":true' \
        && printf '%s' "$EVENTS_JSON" | grep -q '"LOGIN_ERROR"'; then ok 'event log on: login failures, lockouts and admin changes are kept'
    else bad 'event log is off (eventsEnabled / adminEventsEnabled / LOGIN_ERROR)'; fi
    if printf '%s' "$CLIENT" | grep -q '"directAccessGrantsEnabled":true'; then ok 'medlink-bff may exchange a password for tokens (login endpoint)'
    else bad 'medlink-bff: direct grants are off, the login endpoint cannot work'; fi

    # Service account of the API: can manage users, nothing more (least rights).
    ADMIN_CLIENT_ID="$(admin_get '/clients?clientId=medlink-admin' | sed -n 's/^\[{"id":"\([^"]*\)".*/\1/p')"
    if [ -z "$ADMIN_CLIENT_ID" ]; then
        bad 'client medlink-admin not found'
    else
        ADMIN_SECRET="$(admin_get "/clients/$ADMIN_CLIENT_ID/client-secret" | sed -n 's/.*"value":"\([^"]*\)".*/\1/p')"
        if [ "$ADMIN_SECRET" != "$EXPECTED_ADMIN_SECRET" ]; then
            bad 'client medlink-admin: secret differs from what the API uses (KC_ADMIN_CLIENT_SECRET; re-run with --reset)'
        else
            SA_TOKEN="$(curl -s --max-time 15 -d grant_type=client_credentials -d client_id=medlink-admin \
                --data-urlencode "client_secret=$ADMIN_SECRET" \
                "$KEYCLOAK_URL/realms/medlink/protocol/openid-connect/token" |
                sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
            LIST_USERS="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -H "Authorization: Bearer $SA_TOKEN" "$KEYCLOAK_URL/admin/realms/medlink/users?max=1")"
            # GET on the realm itself answers 200 with a short summary to any admin: ask for its clients instead.
            REALM_SETTINGS="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -H "Authorization: Bearer $SA_TOKEN" "$KEYCLOAK_URL/admin/realms/medlink/clients")"
            if [ "$LIST_USERS" = 200 ] && [ "$REALM_SETTINGS" = 403 ]; then ok 'service account medlink-admin: can list users, refused on realm settings (clients)'
            else bad "service account medlink-admin: list users $LIST_USERS (expected 200), realm clients $REALM_SETTINGS (expected 403)"; fi
        fi
    fi

    MISSING="$(missing_values "$(admin_get '/users?briefRepresentation=true&max=50')" username staff.dev practitioner.dev patient.dev)"
    if [ -n "$MISSING" ]; then warn "test users missing: $MISSING (deleted on purpose?)"; else ok 'test users staff.dev, practitioner.dev, patient.dev'; fi
fi

# ---------------------------------------------------------------------------------------------
if [ "$FULL_STACK" -eq 1 ]; then
    step '6/7 Tests skipped in --full-stack mode (the image build compiles the code)'
elif [ "$SKIP_TESTS" -eq 1 ]; then
    step '6/7 Tests skipped (--skip-tests)'
else
    step '6/7 Running ./mvnw verify (first run downloads Maven and dependencies; can take several minutes)'
    TEST_LOG="$LOG_DIR/mvn-verify.log"
    if ./mvnw -B verify > "$TEST_LOG" 2>&1; then
        SUMMARY="$(grep -E 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "$TEST_LOG" | tail -n 1 | sed 's/^\[[A-Z]*\] *//')"
        ok "build and tests passed${SUMMARY:+ ($SUMMARY)}"
    else
        tail -n 60 "$TEST_LOG"
        bad "mvnw verify failed: full log in $TEST_LOG, test reports in $REPO/target/surefire-reports"
    fi
fi

# ---------------------------------------------------------------------------------------------
step '7/7 Starting the API and running smoke tests'

if [ "$FULL_STACK" -eq 0 ]; then
    APP_LOG="$LOG_DIR/app.log"
    SPRING_PROFILES_ACTIVE=dev nohup ./mvnw -B spring-boot:run > "$APP_LOG" 2>&1 &
    echo "$!" > "$PID_FILE"
    ok "API starting in the background (PID $(cat "$PID_FILE"), log $APP_LOG)"
fi

if ! wait_until 600 'the API health endpoint' app_ready; then
    if [ "$FULL_STACK" -eq 1 ]; then docker compose -f "$COMPOSE_FILE" logs --tail 80 app; else tail -n 80 "$LOG_DIR/app.log"; fi
    abort 'The API did not become healthy. See the log above.'
fi

HEALTH="$(curl -s --max-time 10 "$APP_URL/actuator/health")"
case "$HEALTH" in
    *'"status":"UP"'*) ok "health: $HEALTH" ;;
    *) bad "health is not UP: $HEALTH" ;;
esac

STATUS="$(http_status "$APP_URL/api/users")"
if [ "$STATUS" = 401 ]; then ok '/api/users without a session returns 401'; else bad "/api/users without a session returned $STATUS, expected 401"; fi

SWAGGER="$(http_status "$APP_URL/swagger-ui.html")"
case "$SWAGGER" in
    200 | 302) ok 'Swagger UI reachable' ;;
    *) warn "Swagger UI returned $SWAGGER" ;;
esac

# End-to-end: log in as patient.dev through our login endpoint, as React does (CSRF cookie, then
# the JSON post), then call the API with the session. A wrong password first: a successful login
# resets Keycloak's failure count, so repeated runs never lock patient.dev.
COOKIES="$LOG_DIR/cookies.txt"
rm -f "$COOKIES"
curl -s -o /dev/null --max-time 10 -c "$COOKIES" -b "$COOKIES" "$APP_URL/api/auth/csrf"
XSRF="$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$COOKIES" | head -n 1)"
api_login() { # $1 = password -> HTTP status of POST /api/auth/login as patient.dev
    curl -s -o /dev/null -w '%{http_code}' --max-time 30 -c "$COOKIES" -b "$COOKIES" \
        -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
        -d "{\"email\":\"patient.dev@medlink.local\",\"password\":\"$1\"}" \
        "$APP_URL/api/auth/login"
}
STATUS="$(api_login 'Not-The-Password-1!')"
if [ "$STATUS" = 401 ]; then ok 'login with a wrong password returns 401'; else bad "login with a wrong password returned $STATUS, expected 401"; fi
STATUS="$(api_login "$USERS_PASSWORD")"
API_STATUS="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -b "$COOKIES" "$APP_URL/api/users/_health")"
if [ "$STATUS" = 200 ] && [ "$API_STATUS" = 200 ]; then
    ok 'end-to-end login as patient.dev works: POST /api/auth/login 200, then /api/users/_health 200 with the session'
else
    bad "login as patient.dev: POST /api/auth/login returned $STATUS (expected 200), then /api/users/_health $API_STATUS (expected 200)"
fi
rm -f "$COOKIES"

# Signup end to end: CSRF cookie, register, the mail reaches Mailpit, its link verifies the account.
# The smoke account is deleted afterwards (Keycloak user, then our rows).
MAILPIT_URL='http://localhost:8025'
SMOKE_EMAIL="smoke.$(date +%Y%m%d%H%M%S)@medlink.local"
COOKIES="$LOG_DIR/signup-cookies.txt"
rm -f "$COOKIES"
curl -s -o /dev/null --max-time 10 -c "$COOKIES" -b "$COOKIES" "$APP_URL/api/auth/csrf"
XSRF="$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$COOKIES" | head -n 1)"
STATUS="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 -c "$COOKIES" -b "$COOKIES" \
    -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$SMOKE_EMAIL\",\"password\":\"Smoke-Test-2026!\",\"firstName\":\"Smoke\",\"lastName\":\"Test\",\"birthDate\":\"1990-01-01\"}" \
    "$APP_URL/api/auth/register")"
if [ "$STATUS" != 201 ]; then
    bad "signup: POST /api/auth/register returned $STATUS, expected 201 (log: $LOG_DIR/app.log)"
else
    MAIL_ID="$(curl -s --max-time 10 "$MAILPIT_URL/api/v1/search?query=to:%22$SMOKE_EMAIL%22" | sed -n 's/.*"ID":"\([^"]*\)".*/\1/p' | head -n 1)"
    LINK_TOKEN=''
    [ -n "$MAIL_ID" ] && LINK_TOKEN="$(curl -s --max-time 10 "$MAILPIT_URL/api/v1/message/$MAIL_ID" |
        grep -o 'verifier-email?token=[A-Za-z0-9_-]*' | head -n 1 | sed 's/.*token=//')"
    if [ -z "$LINK_TOKEN" ]; then
        bad "signup: no verification mail with a link in Mailpit ($MAILPIT_URL) for $SMOKE_EMAIL"
    else
        STATUS="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 -c "$COOKIES" -b "$COOKIES" \
            -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' -d "{\"token\":\"$LINK_TOKEN\"}" \
            "$APP_URL/api/auth/verify-email")"
        if [ "$STATUS" = 204 ]; then ok 'signup works end to end: register, mail in Mailpit, its link verifies the account'
        else bad "signup: the link from the mail returned $STATUS on /api/auth/verify-email, expected 204"; fi
    fi
fi
TOKEN="$(curl -s --max-time 15 -d grant_type=password -d client_id=admin-cli \
    --data-urlencode "username=$KC_ADMIN_USER" --data-urlencode "password=$KC_ADMIN_PASS" \
    "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
SMOKE_ID="$(admin_get "/users?email=$SMOKE_EMAIL&exact=true" | sed -n 's/^\[{"id":"\([^"]*\)".*/\1/p')"
[ -n "$SMOKE_ID" ] && curl -s -o /dev/null --max-time 15 -X DELETE -H "Authorization: Bearer $TOKEN" "$KEYCLOAK_URL/admin/realms/medlink/users/$SMOKE_ID"
if [ "$FULL_STACK" -eq 1 ]; then DB_USER="$(read_env POSTGRES_USER)"; DB_NAME="$(read_env POSTGRES_DB)"; else DB_USER='myuser'; DB_NAME='mydatabase'; fi
docker compose -f "$COMPOSE_FILE" exec -T postgres psql -q -U "${DB_USER:-myuser}" -d "${DB_NAME:-mydatabase}" \
    -c "delete from users where email = '$SMOKE_EMAIL'" >/dev/null 2>&1 || warn "signup: smoke account rows for $SMOKE_EMAIL not deleted"
rm -f "$COOKIES"

# ---------------------------------------------------------------------------------------------
printf '\n%s------------------------------------------------------------%s\n' "$C_STEP" "$C_OFF"
if [ "$FAILURES" -eq 0 ]; then
    printf '%sapi-MedLink is up. %s warning(s).%s\n' "$C_OK" "$WARNINGS" "$C_OFF"
else
    printf '%sapi-MedLink is up with %s failed check(s) and %s warning(s). See [FAIL] lines above.%s\n' "$C_BAD" "$FAILURES" "$WARNINGS" "$C_OFF"
fi
if [ "$FULL_STACK" -eq 1 ]; then
    ADMIN_PASSWORD_HINT='KEYCLOAK_ADMIN_PASSWORD in .env'
    USERS_PASSWORD_HINT='KC_DEV_USERS_PASSWORD in .env'
else
    ADMIN_PASSWORD_HINT='admin'
    USERS_PASSWORD_HINT="$DEV_USERS_PASSWORD"
fi
cat <<EOF

  API             $APP_URL
  Login           POST $APP_URL/api/auth/login   (JSON email + password, X-XSRF-TOKEN header)
  Swagger UI      $APP_URL/swagger-ui.html
  Mail inbox      http://localhost:8025   (every mail the API sends, Mailpit)
  Keycloak admin  $KEYCLOAK_URL   (user: $KC_ADMIN_USER, password: $ADMIN_PASSWORD_HINT)
  Test users      staff.dev / practitioner.dev / patient.dev   (password: $USERS_PASSWORD_HINT)
  Logs            $LOG_DIR

  Stop everything:  $REPO/scripts/setup-dev.sh --stop
  Fresh realm:      re-run with --reset   (deletes Keycloak and app data; automatic when the realm file changes)
EOF
[ "$FAILURES" -gt 0 ] && exit 1
exit 0
