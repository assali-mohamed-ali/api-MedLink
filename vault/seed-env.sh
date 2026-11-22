#!/bin/sh
# Fills the API's secrets for one environment in Vault (MLink-168, MLink-172). Run it on the vault host:
#
#   VAULT_ADDR=http://127.0.0.1:8200 VAULT_TOKEN=<token with policy medlink-setup> sh vault/seed-env.sh <staging|prod>
#
# Each missing key of medlink/<env>/api gets a fresh random value, generated here (never typed, never printed).
# A key that already exists is never changed: rotating a secret is a separate, deliberate step (docs/operations/vault.md).
# MAIL_PASSWORD (the Gmail app password) is the only value typed by a person: asked with a hidden prompt when this
# runs in a terminal; otherwise left missing and reported.
set -eu

die() { echo "seed-env: $*" >&2; exit 2; }
say() { echo "seed-env: $*"; }

[ $# -eq 1 ] || die "usage: seed-env.sh <staging|prod>"
case "$1" in
    staging|prod) ENV="$1" ;;
    *) die "unknown environment '$1': staging or prod" ;;
esac
: "${VAULT_ADDR:?VAULT_ADDR must be set}"
: "${VAULT_TOKEN:?VAULT_TOKEN must be set: a token with the medlink-setup policy}"
export VAULT_ADDR VAULT_TOKEN
P="medlink/$ENV/api"

rand_b64() { head -c "$1" /dev/urandom | base64 | tr -d '\n'; }
# Letters and digits only (safe in .env, URLs and JSON), then a fixed tail so the realm policy's classes are met
password() { printf '%s-Mk7' "$(rand_b64 24 | tr -d '+/=')"; }
json_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }

has() { vault kv get -field="$1" "$P" > /dev/null 2>&1; }
exists=no
vault kv metadata get "$P" > /dev/null 2>&1 && exists=yes

JSON=""
added=""
add() { # $1 key, $2 value
    JSON="${JSON:+$JSON,}\"$1\":\"$(json_escape "$2")\""
    added="$added $1"
}
for key in POSTGRES_PASSWORD APP_DB_PASSWORD KEYCLOAK_DB_PASSWORD KC_BFF_CLIENT_SECRET KC_ADMIN_CLIENT_SECRET; do
    has "$key" || add "$key" "$(rand_b64 32 | tr -d '+/=')"
done
for key in KEYCLOAK_ADMIN_PASSWORD KC_DEV_USERS_PASSWORD; do
    has "$key" || add "$key" "$(password)"
done
# 32 bytes in base64, as MfaSecretCipher, the report cipher and the documents cipher (MLink-9) require
for key in APP_MFA_ENCRYPTION_KEY APP_REPORT_ENCRYPTION_KEY APP_DOCUMENTS_ENCRYPTION_KEY; do
    has "$key" || add "$key" "$(rand_b64 32)"
done
# The host's MinIO (MLink-9): MinIO refuses a root user outside 3..20 characters or a password outside 8..40
has MINIO_ROOT_USER || add MINIO_ROOT_USER "medlink-$(rand_b64 12 | tr -d '+/=' | cut -c1-12)"
has MINIO_ROOT_PASSWORD || add MINIO_ROOT_PASSWORD "$(rand_b64 33 | tr -d '+/=' | cut -c1-40)"
# Backups (GitHub #67): at least 20 characters
has BACKUP_PASSPHRASE || add BACKUP_PASSPHRASE "$(rand_b64 33)"

if ! has MAIL_PASSWORD; then
    if [ -t 0 ]; then
        printf 'Gmail app password for the MedLink mail account (hidden, spaces ignored): ' >&2
        stty -echo
        IFS= read -r mail || true
        stty echo
        printf '\n' >&2
        mail="$(printf '%s' "$mail" | tr -d ' ')"
        if [ -n "$mail" ]; then add MAIL_PASSWORD "$mail"; else say "MAIL_PASSWORD left missing (empty input)"; fi
        mail=""
    else
        say "MAIL_PASSWORD missing: run this again from a terminal to type it (hidden prompt)"
    fi
fi

if [ -z "$JSON" ]; then
    say "$P: nothing missing, nothing changed"
    exit 0
fi
if [ "$exists" = yes ]; then
    printf '{%s}' "$JSON" | vault kv patch "$P" - > /dev/null
else
    printf '{%s}' "$JSON" | vault kv put -cas=0 "$P" - > /dev/null
fi
JSON=""
say "$P: added$added"
