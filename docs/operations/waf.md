# Review the Caddy WAF on staging

The image in `deploy/waf/Dockerfile` builds Caddy with the Coraza module. The
`medlink-waf.caddy` snippet uses the packaged OWASP Core Rule Set and orders
inspection before upstream forwarding. It allows up to 12 MiB of request body
to accommodate the application's 10 MiB document limit and multipart overhead.
Coraza audit logging is off because request details can contain credentials or
patient data.

Import the snippet at the top level of the deployed Caddyfile, then import
`medlink_waf <existing-api-upstream>` in the existing API-only route. Keep the
current TLS, dashboard routes and upstream addresses. Retain the original
Caddyfile and image reference for rollback. The staging API binds to the host
loopback interface. A replacement Caddy container needs host networking to
reach that upstream at its existing address.

Before enabling the WAF on staging, run Caddy validation against the complete
staging config. Check login, CSRF, appointment booking and document upload at
the size limit. Review any blocked legitimate traffic before changing rules.
Confirm that only the intended HTTPS ingress is public using the deployed
security-group rules and host listeners.

Sources

- [Coraza Caddy module and packaged CRS](https://github.com/corazawaf/coraza-caddy)
