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
