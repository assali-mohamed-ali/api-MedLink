#!/bin/sh
# Daily raft snapshot of the MedLink Vault (MLink-168), run by root's cron on the vault host (vault/install-host.sh).
# A snapshot is encrypted by Vault's barrier: it restores only with the unseal keys. Keeps KEEP_DAYS days on the host;
# the off-host copy is a separate step (docs/operations/vault.md). Its token (role medlink-snapshot) can only take
# snapshots; each run renews it, so it never expires while the job runs at least once every 32 days.
set -eu
umask 077

TOKEN_FILE="${TOKEN_FILE:-/etc/vault.d/snapshot.token}"
DIR="${SNAPSHOT_DIR:-/var/backups/vault}"
KEEP_DAYS="${KEEP_DAYS:-7}"
VAULT_ADDR="${VAULT_ADDR:-http://127.0.0.1:8200}"
[ -s "$TOKEN_FILE" ] || { echo "vault-snapshot: no token in $TOKEN_FILE (run vault/install-host.sh)" >&2; exit 2; }
VAULT_TOKEN="$(cat "$TOKEN_FILE")"
export VAULT_ADDR VAULT_TOKEN

vault token renew > /dev/null
out="$DIR/vault-$(date -u +%Y%m%dT%H%M%SZ).snap"
vault operator raft snapshot save "$out.part"
mv "$out.part" "$out"
find "$DIR" -maxdepth 1 -name 'vault-*.snap' -mtime +"$KEEP_DAYS" -delete
find "$DIR" -maxdepth 1 -name 'vault-*.snap.part' -mmin +60 -delete
echo "vault-snapshot: $out ($(wc -c < "$out" | tr -d ' ') bytes)"
