# MedLink monitoring

Run the central stack in `deploy/monitoring/docker-compose.monitoring.yml`. Configure
its target directory and secret files with absolute paths. Grafana is bound to
loopback on port 3000. Access it through a tunnel or your existing authenticated
operations gateway. Prometheus and Alertmanager stay on the Compose network.

- On the two API hosts, allow TCP 9000 in the security group from the monitoring host’s private IP or security group. Bind the metrics port to the API host’s private address with `API_METRICS_BIND_ADDRESS` and add `deploy/monitoring/docker-compose.metrics.yml` after the server override. Keep the same override in subsequent API deploys.
- On each of the four hosts, allow TCP 9100 only from the monitoring host. Set `NODE_EXPORTER_BIND_ADDRESS` to that host’s private address and start `deploy/monitoring/docker-compose.node.yml`.
- Place `api-staging.yml`, `api-prod.yml`, and `node.yml` in `MONITORING_TARGETS_DIR`. Each file uses Prometheus file discovery format, for example `- targets: ['10.0.0.10:9000']`. Use real private addresses. The node file lists all four hosts on port 9100.
- Copy `alertmanager.example.yml` outside the repository. Set the actual SMTP identity and recipient. Point `ALERTMANAGER_CONFIG` to that file and `ALERTMANAGER_SMTP_PASSWORD_FILE` to a restricted file containing the Gmail app password. Set `GRAFANA_PASSWORD_FILE` to a separate restricted file. The corresponding containers must be able to read their mounted secrets.

Check Prometheus targets from inside the stack, then confirm that each of the six
targets is up and send a test alert to the operations mailbox. The provisioned
Grafana dashboard shows API availability, request rate, error rate, p95 latency
and host metrics. Disk alerts cover ext, XFS and Btrfs filesystems.
Run `bash deploy/test/monitoring-config.test.sh` to validate the Compose files,
scrape configuration and alert routing with fixture targets.

### Limits, hardening and dashboards (MLink-175)

The monitoring host also runs staging, and node_exporter runs on the 0.9 GB front and Vault hosts, so every
container is bounded: Prometheus 512 MB, Grafana 256 MB, Alertmanager 64 MB, node_exporter 64 MB. Grafana has no
anonymous viewer and no sign-up, and its usage reporting, update checks and news feed are off. node_exporter shares
the host's pid and network, so it runs with a read-only file system, no capabilities and `no-new-privileges`, and
mounts the host's `/` read-only.

Grafana provisions two dashboards in the **MedLink** folder:

| Dashboard | Panels |
|---|---|
| **MedLink API and hosts** (`spring-boot.json`) | API and host availability, request rate, 5xx rate, p95 latency, filesystem used, JVM heap used and max, database pool (Hikari: active, pending, max) |
| **MedLink hosts** (`hosts.json`) | per host: up, uptime, root disk, CPU, memory, disk space, disk I/O, network |

The hosts dashboard filters on an `env` label when the target files give one, for example
`- targets: ['172.31.40.60:9100']` followed by `  labels: { env: prod }`; without it, "All" shows every host.

Reach Grafana through an SSH tunnel to the monitoring host: `ssh -L 3000:127.0.0.1:3000 ubuntu@<monitoring host>`,
then http://localhost:3000.

`monitoring-config.test.sh` also checks these rules on the files as `docker compose` renders them: images pinned, the
memory limits, only Grafana published and on `127.0.0.1`, Grafana's settings, node_exporter's hardening and that it
refuses to start without `NODE_EXPORTER_BIND_ADDRESS`, and that the dashboards hold the requests, p95, JVM heap,
Hikari, CPU, memory, disk and network panels on the provisioned data source. From Git Bash on Windows, run it with
`MSYS2_ARG_CONV_EXCL='/bin/;/etc/;/work/'` so the paths inside the containers are not rewritten.
`ManagementPortIntegrationTest.prometheusHasWhatTheDashboardsAndAlertsUse` proves the API publishes the request
histogram buckets (the p95 panel and `HighLatencyP95`), JVM heap and Hikari series.

## Application security email

Set `APP_SECURITY_ALERTS_RECIPIENT` on the API to activate security email. It
uses the API’s existing SMTP transport. The default threshold is five audit
events per action, account and source IP in ten minutes. The application checks
once per minute and waits thirty minutes before repeating an alert for that
source. The corresponding Spring properties are
`app.security-alerts.threshold`, `app.security-alerts.window`,
`app.security-alerts.interval`, and `app.security-alerts.cooldown`. Cooldown
state lives in the API process, so a restart or multiple API replicas can
produce duplicate email. The email includes the account ID when available.
