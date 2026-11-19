#!/bin/sh
# Prepares the vault host around Vault itself (MLink-168). Run as root, from the repository copy on the host;
# safe to run again (it rewrites the same files and creates only what is missing):
#
#   sudo sh vault/install-host.sh                       # before setup.sh: audit log directory, rotation, snapshot job
#   sudo VAULT_TOKEN=<setup token> sh vault/install-host.sh   # after setup.sh: also the snapshot job's own token
#
# - /var/log/vault (vault:vault 750) for the audit log, rotated daily, kept 30 days. Vault refuses every request
#   when it cannot write its audit log: rotation keeps the disk from filling.
# - /var/backups/vault (root 700) and a daily snapshot at 02:30 UTC (vault/snapshot.sh), kept 7 days.
# - /etc/vault.d/snapshot.token (root 600): a token of role medlink-snapshot, which can only take snapshots.
set -eu
umask 022

[ "$(id -u)" -eq 0 ] || { echo "install-host: run as root (sudo)" >&2; exit 2; }
id vault > /dev/null 2>&1 || { echo "install-host: no 'vault' user: is Vault installed?" >&2; exit 2; }
HERE="$(cd "$(dirname "$0")" && pwd -P)"
say() { echo "install-host: $*"; }

install -d -o vault -g vault -m 750 /var/log/vault
say "/var/log/vault ready (vault:vault 750)"
install -d -o vault -g vault -m 700 /opt/vault-docker /opt/vault-docker/data
say "/opt/vault-docker/data ready (vault:vault 700)"

cat > /etc/logrotate.d/vault-audit <<'EOF'
# Vault audit log (MedLink, vault/install-host.sh): daily, 30 days; Vault reopens the file on SIGHUP
/var/log/vault/audit.log {
    daily
    rotate 30
    compress
    delaycompress
    missingok
    notifempty
    create 0600 vault vault
    postrotate
        docker kill --signal=HUP medlink-vault > /dev/null 2>&1 || true
    endscript
}
EOF
chmod 644 /etc/logrotate.d/vault-audit
say "audit log rotation: /etc/logrotate.d/vault-audit"

install -d -o root -g root -m 700 /var/backups/vault
install -o root -g root -m 755 "$HERE/snapshot.sh" /usr/local/sbin/medlink-vault-snapshot
cat > /etc/cron.d/medlink-vault-snapshot <<'EOF'
# Daily Vault snapshot (MedLink, vault/install-host.sh)
SHELL=/bin/sh
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
30 2 * * * root /usr/local/sbin/medlink-vault-snapshot >> /var/log/medlink-vault-snapshot.log 2>&1
EOF
chmod 644 /etc/cron.d/medlink-vault-snapshot
say "daily snapshot: 02:30 UTC into /var/backups/vault, kept 7 days"

TOKEN_FILE=/etc/vault.d/snapshot.token
if [ -s "$TOKEN_FILE" ]; then
    say "snapshot token already in $TOKEN_FILE"
elif [ -n "${VAULT_TOKEN:-}" ]; then
    export VAULT_ADDR="${VAULT_ADDR:-http://127.0.0.1:8200}" VAULT_TOKEN
    [ -d /etc/vault.d ] || install -d -m 755 /etc/vault.d   # never re-own Vault's existing config directory
    if ! (umask 077; vault token create -role=medlink-snapshot -field=token > "$TOKEN_FILE.new"); then
        rm -f "$TOKEN_FILE.new"
        echo "install-host: could not create the snapshot token (run setup.sh first)" >&2
        exit 1
    fi
    mv "$TOKEN_FILE.new" "$TOKEN_FILE"
    chown root:root "$TOKEN_FILE"
    chmod 600 "$TOKEN_FILE"
    say "snapshot token written to $TOKEN_FILE (root 600)"
else
    say "snapshot token not created yet: run again with VAULT_TOKEN=<setup token> after setup.sh"
fi
