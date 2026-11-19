#!/bin/sh
# Prepares a MedLink host for the deploys (MLink-171). Run once as root on each host, from a copy of deploy/;
# safe to run again (same files, only what changed is applied):
#
#   sudo sh deploy/prepare-host.sh <staging|api|front> <deploy public key file>
#
#   staging  API, Keycloak, both databases and the front on one host
#   api      production API, Keycloak and both databases
#   front    production front (and the TLS edge); no database
#
# It sets up:
#   - user deploy: no password, in the docker group, one SSH key (the GitHub deploy key, ed25519) marked "restrict"
#     (no terminal, no forwarding). The EC2 launch key of user ubuntu stays, for emergencies only.
#     Being in the docker group is root in practice: the point is a key of its own, revocable on its own.
#   - /opt/medlink/api and/or /opt/medlink/front (deploy, 750)
#   - Docker logs rotated (10 MB x 3 per container); an existing different /etc/docker/daemon.json stops the script
#   - security updates installed automatically (unattended-upgrades; no automatic reboot)
#   - SSH: keys only, no root login, only users ubuntu and deploy (a drop-in, checked with sshd -t before use)
#   - hosts with a database: the daily encrypted backup (GitHub #67) at 02:15 UTC, as deploy, into
#     /var/backups/medlink (7 daily, 4 weekly); it runs once the first deploy has written its configuration.
# Runbook: docs/operations/servers.md. Exit codes: 0 done, 1 a step failed, 2 refused before any change.
set -eu
umask 022

die() { echo "prepare-host: $2" >&2; exit "$1"; }
say() { echo "prepare-host: $*"; }
usage='usage: sudo sh prepare-host.sh <staging|api|front> <deploy public key file>'
HERE="$(cd "$(dirname "$0")" && pwd -P)"

# ---- Checks: nothing is changed until all of them pass ----
[ "$(id -u)" -eq 0 ] || die 2 "run as root (sudo). $usage"
[ $# -eq 2 ] || die 2 "$usage"
case "$1" in
    staging) ROLE=staging; API=yes; FRONT=yes ;;
    api) ROLE=api; API=yes; FRONT=no ;;
    front) ROLE=front; API=no; FRONT=yes ;;
    *) die 2 "unknown role '$1': staging, api or front. $usage" ;;
esac
KEY_FILE="$2"
[ -f "$KEY_FILE" ] || die 2 "no such key file: $KEY_FILE"
grep -q 'PRIVATE KEY' "$KEY_FILE" && die 2 "$KEY_FILE is a private key: give the .pub file, never the private key"
KEY="$(grep -vE '^[[:space:]]*(#|$)' "$KEY_FILE" || true)"
[ "$(printf '%s\n' "$KEY" | grep -c .)" -eq 1 ] || die 2 "$KEY_FILE must hold exactly one public key"
case "$KEY" in ssh-ed25519\ *) ;; *) die 2 "$KEY_FILE is not an ed25519 public key" ;; esac
ssh-keygen -l -f "$KEY_FILE" > /dev/null 2>&1 || die 2 "$KEY_FILE is not a valid public key"
getent group docker > /dev/null || die 2 "no docker group: install Docker first"
grep -qE '^[[:space:]]*Include[[:space:]]+/etc/ssh/sshd_config\.d/\*\.conf' /etc/ssh/sshd_config ||
    die 2 "/etc/ssh/sshd_config does not include /etc/ssh/sshd_config.d/*.conf: the SSH settings would not apply"

DAEMON_JSON='{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}'
squash() { tr -d ' \n\t\r'; }
if [ -f /etc/docker/daemon.json ] &&
    [ "$(squash < /etc/docker/daemon.json)" != "$(printf '%s' "$DAEMON_JSON" | squash)" ]; then
    die 2 "/etc/docker/daemon.json exists with other settings: merge the log rotation by hand (see the runbook), nothing was changed"
fi

systemd_running() { [ -d /run/systemd/system ]; }
# $1 target, $2 owner, $3 mode, content on stdin; prints "changed" when the file was written
put() {
    tmp="$(mktemp)"
    cat > "$tmp"
    if [ -f "$1" ] && cmp -s "$tmp" "$1"; then
        rm -f "$tmp"
    else
        install -o "${2%:*}" -g "${2#*:}" -m "$3" "$tmp" "$1"
        rm -f "$tmp"
        echo changed
    fi
    chown "$2" "$1"
    chmod "$3" "$1"
}
apt_install() { # packages missing from dpkg are installed
    missing=""
    for p in "$@"; do dpkg -s "$p" > /dev/null 2>&1 || missing="$missing $p"; done
    [ -z "$missing" ] && return 0
    apt-get update -qq > /dev/null
    # shellcheck disable=SC2086  # one word per package
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq $missing > /dev/null
    say "installed:$missing"
}

# ---- 1. User deploy and its key ----
if ! id deploy > /dev/null 2>&1; then
    useradd --create-home --shell /bin/bash --user-group deploy
    say "user deploy created (no password)"
fi
usermod -aG docker deploy
install -d -o deploy -g deploy -m 700 /home/deploy/.ssh
printf 'restrict %s\n' "$KEY" | put /home/deploy/.ssh/authorized_keys deploy:deploy 600 > /dev/null
say "deploy: docker group, one restricted key ($(ssh-keygen -l -f "$KEY_FILE" | awk '{print $2}'))"

# ---- 2. Deploy directories ----
install -d -o root -g root -m 755 /opt/medlink
[ "$API" = yes ] && install -d -o deploy -g deploy -m 750 /opt/medlink/api
[ "$FRONT" = yes ] && install -d -o deploy -g deploy -m 750 /opt/medlink/front
say "deploy directories for role $ROLE under /opt/medlink"

# ---- 3. Docker log rotation ----
if [ -n "$(printf '%s\n' "$DAEMON_JSON" | put /etc/docker/daemon.json root:root 644)" ]; then
    if systemd_running; then systemctl restart docker; fi
    say "Docker log rotation set (10 MB x 3; applies to containers created from now on)"
fi

# ---- 4. Security updates ----
apt_install unattended-upgrades
printf 'APT::Periodic::Update-Package-Lists "1";\nAPT::Periodic::Unattended-Upgrade "1";\n' |
    put /etc/apt/apt.conf.d/20auto-upgrades root:root 644 > /dev/null
say "security updates: automatic, daily (no automatic reboot)"

# ---- 5. SSH ----
DROPIN=/etc/ssh/sshd_config.d/10-medlink.conf
backup=""
[ -f "$DROPIN" ] && { backup="$(mktemp)"; cp -p "$DROPIN" "$backup"; }
changed="$(put "$DROPIN" root:root 644 <<'EOF'
# MedLink hosts (deploy/prepare-host.sh): keys only, no root, only the admin and the deploy user
PubkeyAuthentication yes
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
MaxAuthTries 3
X11Forwarding no
AllowUsers ubuntu deploy
EOF
)"
if ! /usr/sbin/sshd -t; then
    if [ -n "$backup" ]; then mv "$backup" "$DROPIN"; else rm -f "$DROPIN"; fi
    die 1 "sshd rejects the new settings: they were removed, SSH is unchanged"
fi
[ -n "$backup" ] && rm -f "$backup"
if [ -n "$changed" ] && systemd_running; then
    systemctl try-reload-or-restart ssh.service 2> /dev/null || true
fi
say "SSH: keys only, no root login, users ubuntu and deploy"

# ---- 6. Backups, where the databases run ----
if [ "$API" = yes ]; then
    apt_install gnupg
    install -d -o deploy -g deploy -m 700 /var/backups/medlink /var/backups/medlink/weekly
    install -d -o deploy -g deploy -m 750 /var/log/medlink
    put /usr/local/bin/medlink-db-backup root:root 755 < "$HERE/db-backup-job.sh" > /dev/null
    put /etc/cron.d/medlink-db-backup root:root 644 > /dev/null <<'EOF'
# Daily encrypted backup of both databases (GitHub #67, deploy/prepare-host.sh), 02:15 UTC, never twice at once
SHELL=/bin/sh
PATH=/usr/local/bin:/usr/bin:/bin
15 2 * * * deploy /usr/bin/flock -n /run/lock/medlink-db-backup.lock /usr/local/bin/medlink-db-backup >> /var/log/medlink/db-backup.log 2>&1
EOF
    put /etc/logrotate.d/medlink root:root 644 > /dev/null <<'EOF'
# MedLink job logs (deploy/prepare-host.sh)
/var/log/medlink/*.log {
    su deploy deploy
    weekly
    rotate 8
    compress
    delaycompress
    missingok
    notifempty
    create 0640 deploy deploy
}
EOF
    say "daily backup at 02:15 UTC into /var/backups/medlink (runs after the first deploy)"
fi

say "done: role $ROLE"
