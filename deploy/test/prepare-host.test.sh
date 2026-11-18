#!/usr/bin/env bash
# Tests deploy/prepare-host.sh (MLink-171) on throwaway Ubuntu 26.04 containers, the release of the MedLink hosts:
#   - refuses to run without root, with an unknown role, without a public key, or with a private key;
#   - creates the deploy user (docker group, one restricted key), the deploy directories of the role, Docker log
#     rotation, security updates, an SSH drop-in that sshd accepts (no password, no root), and, on hosts with a
#     database, the daily backup job; the front host gets no backup job;
#   - never overwrites a different Docker daemon.json; a second run changes nothing; a new key replaces the old one.
# Needs docker. Usage: bash deploy/test/prepare-host.test.sh
# shellcheck disable=SC2015,SC2016  # "check && pass || fail" (pass always succeeds); $(...) in single quotes runs in the container
set -euo pipefail
docker() { MSYS_NO_PATHCONV=1 command docker "$@"; }   # Git Bash: keep container paths as they are

HERE="$(cd "$(dirname "$0")" && pwd -P)"
DEPLOY_DIR="$(cd "$HERE/.." && pwd -P)"
host_path() { if command -v cygpath > /dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
FAILED=0
fail() { echo "FAIL: $*" >&2; FAILED=$((FAILED + 1)); }
pass() { echo "ok:   $*"; }
STAGING="medlink-prepare-staging-$$"
FRONT="medlink-prepare-front-$$"
WORK="$(mktemp -d)"
cleanup() { docker rm -f "$STAGING" "$FRONT" > /dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

# A host as EC2 gives it: Ubuntu 26.04, user ubuntu, Docker installed (its group and /etc/docker), sshd
start_host() {
    docker run -d --name "$1" -v "$(host_path "$DEPLOY_DIR"):/medlink-deploy:ro" ubuntu:26.04 sleep 900 > /dev/null
    docker exec "$1" sh -c 'apt-get update -qq > /dev/null &&
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openssh-server logrotate > /dev/null &&
        (id ubuntu > /dev/null 2>&1 || useradd -m -s /bin/bash ubuntu) && groupadd -f docker && mkdir -p /etc/docker &&
        ssh-keygen -A > /dev/null && mkdir -p /run/sshd &&
        ssh-keygen -q -t ed25519 -N "" -C deploy-test-1 -f /root/deploy1 && ssh-keygen -q -t ed25519 -N "" -C deploy-test-2 -f /root/deploy2'
}
on() { docker exec "$1" sh -c "$2"; }
start_host "$STAGING" &
start_host "$FRONT" &
wait

# 1. Refusals
refused() { # $1 what, $2 command on the staging host
    if on "$STAGING" "$2" > /dev/null 2>&1; then fail "accepted: $1"; else pass "refused: $1"; fi
}
refused "not root"             "su ubuntu -s /bin/sh -c 'sh /medlink-deploy/prepare-host.sh staging /root/deploy1.pub'"
refused "unknown role"         "sh /medlink-deploy/prepare-host.sh qa /root/deploy1.pub"
refused "no key file"          "sh /medlink-deploy/prepare-host.sh staging /root/missing.pub"
refused "a private key"        "sh /medlink-deploy/prepare-host.sh staging /root/deploy1"
refused "not a public key"     "echo hello > /root/junk.pub && sh /medlink-deploy/prepare-host.sh staging /root/junk.pub"
on "$STAGING" 'id deploy' > /dev/null 2>&1 && fail "a refused run created the deploy user" || pass "a refused run changed nothing"

# 2. Staging: API and front on one host, with a database
if on "$STAGING" 'sh /medlink-deploy/prepare-host.sh staging /root/deploy1.pub' > "$WORK"/prepare1.log 2>&1; then
    pass "staging: prepare-host.sh runs"; else fail "staging: prepare-host.sh"; cat "$WORK"/prepare1.log >&2; fi
on "$STAGING" 'id -nG deploy' | tr ' ' '\n' | grep -qx docker && pass "deploy user in the docker group" || fail "deploy not in docker"
on "$STAGING" 'test "$(getent passwd deploy | cut -d: -f7)" = /bin/bash' && pass "deploy user has a shell" || fail "deploy shell"
on "$STAGING" 'passwd -S deploy | awk "{print \$2}"' | grep -qE '^(L|NP)$' && pass "deploy user has no password" ||
    fail "deploy has a usable password"
keys="$(on "$STAGING" 'cat /home/deploy/.ssh/authorized_keys')"
[ "$(printf '%s\n' "$keys" | grep -c .)" = 1 ] && pass "one authorized key" || fail "authorized_keys has $(printf '%s\n' "$keys" | grep -c .) lines"
case "$keys" in restrict\ ssh-ed25519\ *deploy-test-1) pass "the key is restricted (no pty, no forwarding)";;
    *) fail "authorized_keys: $keys";; esac
listing='find /home/deploy/.ssh /home/deploy/.ssh/authorized_keys /opt/medlink /opt/medlink/api /opt/medlink/front /var/backups/medlink /var/backups/medlink/weekly /var/log/medlink /etc/docker/daemon.json /etc/apt/apt.conf.d/20auto-upgrades /etc/ssh/sshd_config.d/10-medlink.conf /etc/cron.d/medlink-db-backup /etc/logrotate.d/medlink /usr/local/bin/medlink-db-backup -maxdepth 0 -printf "%p %u:%g %m\n" 2>/dev/null; md5sum /home/deploy/.ssh/authorized_keys /etc/docker/daemon.json /etc/apt/apt.conf.d/20auto-upgrades /etc/ssh/sshd_config.d/10-medlink.conf /etc/cron.d/medlink-db-backup /etc/logrotate.d/medlink /usr/local/bin/medlink-db-backup 2>/dev/null'
on "$STAGING" "$listing" > "$WORK"/staging1.lst || true
expect() { # $1 line expected in the listing of $2
    grep -qxF "$1" "$2" && pass "$1" || fail "expected '$1'"
}
expect "/home/deploy/.ssh deploy:deploy 700" "$WORK"/staging1.lst
expect "/home/deploy/.ssh/authorized_keys deploy:deploy 600" "$WORK"/staging1.lst
expect "/opt/medlink root:root 755" "$WORK"/staging1.lst
expect "/opt/medlink/api deploy:deploy 750" "$WORK"/staging1.lst
expect "/opt/medlink/front deploy:deploy 750" "$WORK"/staging1.lst
expect "/var/backups/medlink deploy:deploy 700" "$WORK"/staging1.lst
expect "/var/backups/medlink/weekly deploy:deploy 700" "$WORK"/staging1.lst
expect "/var/log/medlink deploy:deploy 750" "$WORK"/staging1.lst
expect "/etc/cron.d/medlink-db-backup root:root 644" "$WORK"/staging1.lst
expect "/usr/local/bin/medlink-db-backup root:root 755" "$WORK"/staging1.lst
daemon="$(on "$STAGING" 'tr -d " \n" < /etc/docker/daemon.json')"
[ "$daemon" = '{"log-driver":"json-file","log-opts":{"max-size":"10m","max-file":"3"}}' ] &&
    pass "Docker logs rotated (10 MB x 3)" || fail "daemon.json: $daemon"
on "$STAGING" 'grep -q "Unattended-Upgrade \"1\"" /etc/apt/apt.conf.d/20auto-upgrades && grep -q "Update-Package-Lists \"1\"" /etc/apt/apt.conf.d/20auto-upgrades && dpkg -s unattended-upgrades > /dev/null 2>&1' &&
    pass "security updates installed and enabled" || fail "unattended upgrades"
on "$STAGING" '/usr/sbin/sshd -t' && pass "sshd accepts its configuration" || fail "sshd -t rejects the configuration"
sshd_effective="$(on "$STAGING" '/usr/sbin/sshd -T -C user=deploy,host=test,addr=192.0.2.1 2>/dev/null')"
for setting in "passwordauthentication no" "kbdinteractiveauthentication no" "permitrootlogin no" \
    "pubkeyauthentication yes" "x11forwarding no" "maxauthtries 3" "allowusers ubuntu" "allowusers deploy"; do
    printf '%s\n' "$sshd_effective" | grep -qx "$setting" && pass "sshd: $setting" || fail "sshd effective config lacks '$setting'"
done
on "$STAGING" 'grep -E "^[^#]" /etc/cron.d/medlink-db-backup' | grep -qE '^15 2 \* \* \* deploy /usr/bin/flock -n [^ ]+ /usr/local/bin/medlink-db-backup' &&
    pass "daily backup at 02:15 UTC as deploy, never twice at once" || fail "backup cron line"
on "$STAGING" 'logrotate -d /etc/logrotate.d/medlink' > "$WORK"/logrotate.log 2>&1 && ! grep -qi error "$WORK"/logrotate.log &&
    pass "MedLink logs rotation is valid" || { fail "logrotate rejects /etc/logrotate.d/medlink"; cat "$WORK"/logrotate.log >&2; }
if on "$STAGING" 'su deploy -s /bin/sh -c /usr/local/bin/medlink-db-backup' > "$WORK"/backup.log 2>&1; then
    fail "the backup job ran without its configuration"
else grep -q "not configured" "$WORK"/backup.log && pass "the backup job refuses to run before its first deploy" ||
    fail "backup job message: $(cat "$WORK"/backup.log)"; fi

# 3. A second run changes nothing; a new key replaces the old one
on "$STAGING" 'sh /medlink-deploy/prepare-host.sh staging /root/deploy1.pub' > /dev/null 2>&1 || fail "staging: second run"
on "$STAGING" "$listing" > "$WORK"/staging2.lst || true
diff -q "$WORK"/staging1.lst "$WORK"/staging2.lst > /dev/null && pass "second run changes nothing" || { fail "second run changed the host"; diff "$WORK"/staging1.lst "$WORK"/staging2.lst >&2; }
on "$STAGING" 'sh /medlink-deploy/prepare-host.sh staging /root/deploy2.pub' > /dev/null 2>&1 || fail "staging: run with a new key"
keys="$(on "$STAGING" 'cat /home/deploy/.ssh/authorized_keys')"
case "$keys" in restrict\ ssh-ed25519\ *deploy-test-2) pass "a new key replaces the old one";; *) fail "after rotation: $keys";; esac

# 4. Front: no database, so no API directory and no backup job; a different daemon.json is never overwritten
on "$FRONT" 'printf "{\"data-root\": \"/srv/docker\"}\n" > /etc/docker/daemon.json'
if on "$FRONT" 'sh /medlink-deploy/prepare-host.sh front /root/deploy1.pub' > /dev/null 2>&1; then
    fail "front: ran over a different daemon.json"; else pass "front: refuses to overwrite a different daemon.json"; fi
on "$FRONT" 'grep -q data-root /etc/docker/daemon.json' && pass "front: daemon.json left as it was" || fail "front: daemon.json changed"
on "$FRONT" 'rm /etc/docker/daemon.json'
if on "$FRONT" 'sh /medlink-deploy/prepare-host.sh front /root/deploy1.pub' > "$WORK"/front.log 2>&1; then
    pass "front: prepare-host.sh runs"; else fail "front: prepare-host.sh"; cat "$WORK"/front.log >&2; fi
on "$FRONT" "$listing" > "$WORK"/front.lst || true
expect "/opt/medlink/front deploy:deploy 750" "$WORK"/front.lst
for absent in /opt/medlink/api /var/backups/medlink /etc/cron.d/medlink-db-backup /usr/local/bin/medlink-db-backup; do
    grep -q "^$absent " "$WORK"/front.lst && fail "front: $absent exists" || pass "front: no $absent"
done

echo
if [ "$FAILED" -eq 0 ]; then echo "prepare-host: all checks passed"; else echo "prepare-host: $FAILED check(s) failed"; exit 1; fi
