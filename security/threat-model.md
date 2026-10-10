# Threat Model — api-MedLink (STRIDE-lite)

> API santé : gestion d'utilisateurs, données potentiellement sensibles (PII/PHI).
> Hypothèses : Postgres managé, TLS terminé au reverse-proxy, auth déléguée à Keycloak (BFF : session cookie, tokens côté serveur),
> images via GHCR, déploiement Docker/Terraform.

## Actifs
- Comptes utilisateurs (username/email côté API ; mots de passe et rôles dans Keycloak uniquement)
- Realm Keycloak `medlink` (`keycloak/realm-medlink.json`), secrets (`KC_BFF_CLIENT_SECRET`, admin Keycloak, mots de passe DB)
- Postgres + migrations Flyway (`V1__init.sql`, `V2__keycloak_users.sql`), base Keycloak séparée (`keycloak-db`)
- Observabilité (Prometheus `/actuator/prometheus`)

## Frontières de confiance
1. Client <-> API (`/api/**`, OpenAPI/Swagger)
2. API <-> Postgres (JDBC, secrets via env)
5. Navigateur <-> Keycloak (login OIDC) et API <-> Keycloak (échange de code, JWKS)
3. CI/CD <-> GHCR / Terraform (OIDC `GITHUB_TOKEN`, pas de secret long-lived)
4. Actuator public minimal (`health/info/prometheus` lectures seules)

## Menaces (extrait) + mitigations
| # | STRIDE | Scénario | Mitigation en place / prévue |
|---|--------|----------|------------------------------|
| 1 | Spoofing | Vol de session / de token | Keycloak (code + PKCE, client confidentiel), tokens jamais exposés au navigateur, CSRF actif, brute-force protection + password policy dans le realm |
| 2 | Tampering | Injection SQL | JPA paramétré, Flyway versionné, `ddl-auto: validate` en prod |
| 3 | Repudiation | Actions sans trace | Logs structurés, actuator + Prometheus, alertes |
| 4 | Info disclosure | PII exposée via API/actuator | DTO sans password, actuator restreint, CORS allowlist, Gitleaks |
| 5 | DoS | Abus `/api/users` | Rate-limit au reverse-proxy (TODO), healthchecks, pool Hikari borné |
| 6 | EoP | Rôle forgé | Rôles attribués uniquement via groupes Keycloak (claim `roles` signé), plus de rôle stocké/accepté par l'API |
| 7 | Supply-chain | Dépendance/image vulnérable | Dependabot, Dependency-Check, Trivy FS+image, CodeQL |

## TODO sécurité
- [ ] Endpoints `AuthBffController` (me/logout) + tests d'autorisation négatifs
- [ ] Supprimer les comptes de test Keycloak (`*.dev`) en staging/prod
- [ ] Rate-limit + headers (HSTS/CSP) au reverse-proxy, durcir `zap-rules.tsv`
- [ ] Chiffrement at-rest Postgres + backups chiffrés, rotation `KC_BFF_CLIENT_SECRET`
- [ ] Logout OIDC (fin de session Keycloak) côté BFF
