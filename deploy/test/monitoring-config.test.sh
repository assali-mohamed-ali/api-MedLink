#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

fixtures="$(mktemp -d)"
trap 'rm -rf "$fixtures"' EXIT
mkdir -m 755 "$fixtures/targets"
printf '%s\n' "- targets: ['192.0.2.1:9000']" > "$fixtures/targets/api-staging.yml"
printf '%s\n' "- targets: ['192.0.2.2:9000']" > "$fixtures/targets/api-prod.yml"
printf '%s\n' "- targets: ['192.0.2.1:9100', '192.0.2.2:9100', '192.0.2.3:9100', '192.0.2.4:9100']" \
    > "$fixtures/targets/node.yml"

MONITORING_TARGETS_DIR="$fixtures/targets" \
ALERTMANAGER_CONFIG="$PWD/deploy/monitoring/alertmanager.example.yml" \
ALERTMANAGER_SMTP_PASSWORD_FILE="$fixtures/unused-smtp-password" \
GRAFANA_PASSWORD_FILE="$fixtures/unused-grafana-password" \
    docker compose -f deploy/monitoring/docker-compose.monitoring.yml config --quiet

NODE_EXPORTER_BIND_ADDRESS=127.0.0.1 \
    docker compose -f deploy/monitoring/docker-compose.node.yml config --quiet

docker run --rm --entrypoint /bin/promtool \
    -v "$PWD/deploy/monitoring/prometheus.yml:/etc/prometheus/prometheus.yml:ro" \
    -v "$fixtures/targets:/etc/prometheus/targets:ro" \
    -v "$PWD/monitoring/alerts/alert-rules.yml:/etc/prometheus/alert-rules.yml:ro" \
    prom/prometheus:v3.15.0 check config /etc/prometheus/prometheus.yml

docker run --rm --entrypoint /bin/amtool \
    -v "$PWD/deploy/monitoring/alertmanager.example.yml:/etc/alertmanager/alertmanager.yml:ro" \
    prom/alertmanager:v0.35.1 check-config /etc/alertmanager/alertmanager.yml

# MLink-175 gaps: the hosts are small (the front and Vault have 0.9 GB), nothing is public, and the dashboards show
# what the ticket's "Done when" lists. Checked on the files as docker compose renders them.
MONITORING_TARGETS_DIR="$fixtures/targets" \
ALERTMANAGER_CONFIG="$PWD/deploy/monitoring/alertmanager.example.yml" \
ALERTMANAGER_SMTP_PASSWORD_FILE="$fixtures/unused-smtp-password" \
GRAFANA_PASSWORD_FILE="$fixtures/unused-grafana-password" \
    docker compose -f deploy/monitoring/docker-compose.monitoring.yml config --format json > "$fixtures/stack.json"
NODE_EXPORTER_BIND_ADDRESS=192.0.2.1 \
    docker compose -f deploy/monitoring/docker-compose.node.yml config --format json > "$fixtures/node.json"
(unset NODE_EXPORTER_BIND_ADDRESS; docker compose -f deploy/monitoring/docker-compose.node.yml config --quiet 2> /dev/null) &&
    { echo "FAIL: node_exporter starts without NODE_EXPORTER_BIND_ADDRESS" >&2; exit 1; }
cp monitoring/grafana/dashboards/*.json "$fixtures/"

cat > "$fixtures/rules.py" <<'PY'
import glob, json, re, sys
failed = 0
def check(ok, what):
    global failed
    print(("ok:   " if ok else "FAIL: ") + what)
    failed += not ok
MB = 1024 * 1024
stack = json.load(open("/work/stack.json"))["services"]
node = json.load(open("/work/node.json"))["services"]["node-exporter"]
limits = {"prometheus": 512, "alertmanager": 64, "grafana": 256}
for name, svc in sorted(stack.items()):
    check(bool(re.search(r":v?\d+\.\d+\.\d+$", svc.get("image", ""))), f"{name}: image pinned ({svc.get('image')})")
    check(0 < int(svc.get("mem_limit") or 0) <= limits.get(name, 0) * MB, f"{name}: memory limit of {limits.get(name, 0)} MB at most")
    for port in svc.get("ports", []):
        check(port.get("host_ip") == "127.0.0.1", f"{name}: port {port.get('published')} on 127.0.0.1 only")
check(not stack.get("prometheus", {}).get("ports") and not stack.get("alertmanager", {}).get("ports"),
      "prometheus and alertmanager: no published port")
env = stack.get("grafana", {}).get("environment", {})
for key in ("GF_AUTH_ANONYMOUS_ENABLED", "GF_USERS_ALLOW_SIGN_UP", "GF_ANALYTICS_REPORTING_ENABLED",
            "GF_ANALYTICS_CHECK_FOR_UPDATES", "GF_ANALYTICS_CHECK_FOR_PLUGIN_UPDATES", "GF_NEWS_NEWS_FEED_ENABLED"):
    check(env.get(key) == "false", f"grafana: {key}=false")
command = node.get("command") or []
check("--web.listen-address=192.0.2.1:9100" in command, "node-exporter: listens on NODE_EXPORTER_BIND_ADDRESS only")
check(0 < int(node.get("mem_limit") or 0) <= 64 * MB, "node-exporter: memory limit of 64 MB at most")
check(node.get("read_only") is True, "node-exporter: read-only file system")
check(node.get("cap_drop") == ["ALL"], "node-exporter: no capabilities")
check("no-new-privileges:true" in (node.get("security_opt") or []), "node-exporter: no-new-privileges")
root = [v for v in node.get("volumes", []) if v.get("target") == "/host"]
check(len(root) == 1 and root[0].get("read_only"), "node-exporter: the host's / mounted read-only")
# The panels the ticket asks for, each on the provisioned data source
dashboards = [json.load(open(f)) for f in sorted(glob.glob("/work/*.json")) if not f.endswith(("stack.json", "node.json"))]
exprs = " ".join(t.get("expr", "") for d in dashboards for p in d.get("panels", []) for t in p.get("targets", []))
for metric, what in (("http_server_requests_seconds_count", "requests"), ("http_server_requests_seconds_bucket", "p95 latency"),
                     ("jvm_memory_used_bytes", "JVM heap"), ("hikaricp_connections_active", "database pool"),
                     ("node_cpu_seconds_total", "CPU"), ("node_memory_MemAvailable_bytes", "memory"),
                     ("node_filesystem_avail_bytes", "disk"), ("node_network_receive_bytes_total", "network")):
    check(metric in exprs, f"dashboards: a {what} panel ({metric})")
uids = {p.get("datasource", {}).get("uid") for d in dashboards for p in d.get("panels", []) if p.get("datasource")}
check(uids == {"medlink-prometheus"}, f"dashboards: every panel on the provisioned data source ({sorted(map(str, uids))})")
sys.exit(1 if failed else 0)
PY
# cygpath: a Windows path for Docker Desktop when run from Git Bash; Linux and macOS use the path as it is
work="$fixtures"; if command -v cygpath > /dev/null 2>&1; then work="$(cygpath -w "$fixtures")"; fi
docker run --rm -v "$work:/work:ro" python:3.13-alpine python3 /work/rules.py
