#!/bin/bash
# Dev only (compose.yaml, service keycloak-dev-users; never in docker-compose.yml or a deployment).
# Adds the dev practitioners of dev-practitioners.json to the medlink realm through Keycloak's partial
# import, which keeps their fixed ids (the dev seed db/dev-seed uses them). Users that already exist are
# skipped, so this runs at every start. The password placeholder is filled from the environment here:
# a partial import, unlike the realm import, does not resolve ${...} placeholders.
set -euo pipefail
shopt -u patsub_replacement 2>/dev/null || true

KCADM=/opt/keycloak/bin/kcadm.sh
CONFIG=/tmp/kcadm.config
SOURCE=/opt/keycloak/dev/dev-practitioners.json
BODY=/tmp/dev-practitioners.json

"$KCADM" config credentials --config "$CONFIG" --server "$KEYCLOAK_URL" --realm master \
    --user "$DEV_ADMIN_USERNAME" --password "$DEV_ADMIN_PASSWORD"

json=$(< "$SOURCE")
printf '%s' "${json//'${KC_DEV_USERS_PASSWORD}'/$KC_DEV_USERS_PASSWORD}" > "$BODY"

"$KCADM" create partialImport --config "$CONFIG" -r medlink -f "$BODY" -o
echo "dev practitioners imported into realm medlink (existing users skipped)"
