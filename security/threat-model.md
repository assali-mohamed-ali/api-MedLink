# Threat Model — api-MedLink (STRIDE-lite)

> API santé : gestion d'utilisateurs, données potentiellement sensibles (PII/PHI).
> Hypothèses : Postgres managé, TLS terminé au reverse-proxy, JWT en Bearer,
> images via GHCR, déploiement Docker/Terraform.

## Actifs
- Comptes utilisateurs (username/email/hash bcrypt), rôles
- JWT (émission/validation), secrets (`JWT_SECRET`, mots de passe DB)
- Postgres + migrations Flyway (`V1__init.sql`)
- Observabilité (Prometheus `/actuator/prometheus`)

## Frontières de confiance
1. Client <-> API (`/api/**`, OpenAPI/Swagger)
2. API <-> Postgres (JDBC, secrets via env)
3. CI/CD <-> GHCR / Terraform (OIDC `GITHUB_TOKEN`, pas de secret long-lived)
4. Actuator public minimal (`health/info/prometheus` lectures seules)

## Menaces (extrait) + mitigations
| # | STRIDE | Scénario | Mitigation en place / prévue |
|---|--------|----------|------------------------------|
| 1 | Spoofing | Vol/forgage de JWT | Secret 32+ chars via env, expiration 1h, filtre `JwtAuthFilter`, `SecurityConfig` stateless |
| 2 | Tampering | Injection SQL | JPA paramétré, Flyway versionné, `ddl-auto: validate` en prod |
| 3 | Repudiation | Actions sans trace | Logs structurés, actuator + Prometheus, alertes |
| 4 | Info disclosure | PII exposée via API/actuator | DTO sans password, actuator restreint, CORS allowlist, Gitleaks |
| 5 | DoS | Abus `/api/users` | Rate-limit au reverse-proxy (TODO), healthchecks, pool Hikari borné |
| 6 | EoP | `role` forgé à la création | Durcir `UserService.create` (forcer `USER` sauf admin) — TODO |
| 7 | Supply-chain | Dépendance/image vulnérable | Dependabot groupé, Dependency-Check BLOQUANT (CVSS>=7), Trivy config+image BLOQUANT (CRITICAL/HIGH, SARIF), CodeQL `security-extended` |

## TODO sécurité
- [ ] Endpoint `/api/auth/**` (login/refresh) + tests d'autorisation négatifs
- [ ] Rate-limit + headers (HSTS/CSP) au reverse-proxy, durcir `zap-rules.tsv`
- [ ] Chiffrement at-rest Postgres + backups chiffrés, rotation `JWT_SECRET`
- [ ] Revocation JWT (deny-list) si besoin métier
