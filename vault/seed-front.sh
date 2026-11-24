#!/bin/sh
# Fills the dashboard's SSH deploy bundle in Vault (MLink-168): the GitHub OIDC role
# front-staging/front-production reads medlink/<env>/front. Run it on the vault host:
#
#   VAULT_ADDR=http://127.0.0.1:8200 VAULT_TOKEN=<token with policy medlink-setup> \
#     sh vault/seed-front.sh <staging|prod> <ssh-private-key> <known-hosts>
#
# Each missing key gets the value read from the file. An existing key is never changed:
# rotating a deploy key is a separate, deliberate step (docs/operations/vault.md).
set -eu

die() { echo "seed-front: $*" >&2; exit 2; }
say() { echo "seed-front: $*"; }

[ $# -eq 3 ] || die "usage: seed-front.sh <staging|prod> <ssh-private-key> <known-hosts>"
case "$1" in
    staging|prod) ENV="$1" ;;
    *) die "unknown environment '$1': staging or prod" ;;
esac
KEY_FILE="$2"
KNOWN_HOSTS_FILE="$3"
[ -r "$KEY_FILE" ] || die "cannot read the SSH private key"
[ -r "$KNOWN_HOSTS_FILE" ] || die "cannot read the known_hosts file"
[ -s "$KNOWN_HOSTS_FILE" ] || die "the known_hosts file is empty"
grep -q 'BEGIN .*PRIVATE KEY' "$KEY_FILE" || die "the SSH key is not a private key"
: "${VAULT_ADDR:?VAULT_ADDR must be set}"
: "${VAULT_TOKEN:?VAULT_TOKEN must be set: a token with the medlink-setup policy}"
export VAULT_ADDR VAULT_TOKEN
P="medlink/$ENV/front"

base64_file() { base64 < "$1" | tr -d '\n'; }
json_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }
has() { vault kv get -field="$1" "$P" > /dev/null 2>&1; }
exists=no
vault kv metadata get "$P" > /dev/null 2>&1 && exists=yes

JSON=""
added=""
add() {
    JSON="${JSON:+$JSON,}\"$1\":\"$(json_escape "$2")\""
    added="$added $1"
}
has ssh_private_key_b64 || add ssh_private_key_b64 "$(base64_file "$KEY_FILE")"
has known_hosts_b64 || add known_hosts_b64 "$(base64_file "$KNOWN_HOSTS_FILE")"

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
