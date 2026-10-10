# Politique de sécurité — api-MedLink

## Versions supportées

| Version | Support |
|---------|---------|
| `master` / `staging` / `prod` (dernières) | :white_check_mark: |
| tags `v*` maintenus 3 mois | :white_check_mark: |
| autres branches | :x: |

## Gates bloquants (CI requis en protection de branche)

- `CI` : `./mvnw verify` — tout test rouge bloque le merge. Artefacts
  `surefire-reports` + `jacoco-report` publiés à chaque run.
- `SAST-CodeQL` : requêtes `security-extended` — alertes code-scanning
  bloquantes une fois marquées required.
- `SCA-Dependency-Check` : échoue sur CVSS >= 7 (SARIF publié).
  Seule échappatoire : suppression justifiée dans
  `security/owasp-suppressions.xml` (expiry + ticket).
- `Secrets-Scan` (Gitleaks) : tout leak bloque.
- `Container-Scan` (Trivy config+image) : CRITICAL/HIGH bloque (SARIF publié).
- `DAST-ZAP` : `fail_action: true` bloque sur violation des règles.
- `CD` : `staging` → env `staging` ; seuls `prod` / `v*` atteignent
  l'env `production` (reviewers requis).

## Signaler une vulnérabilité

- **Ne pas ouvrir d'issue publique.** Écrivez à : `security@medlink.example.com`
  (ou DM au mainteneur `@assali-mohamed-ali` en précisant `[SECURITY]`).
- Incluez : version/commit, steps to reproduce, impact, PoC/logs, CVE si connue.
- Engagement : accusé sous **48h ouvrées**, correctif ou plan sous **14 jours** pour critique/haute.

## Périmètre

- `src/main/java/com/example/apimedlink/**` (auth BFF Keycloak, `UserController`, JPA/Flyway)
- `keycloak/realm-medlink.json` (realm, rôles, client)
- `docker/Dockerfile`, `docker-compose.yml`, `terraform/**`
- Workflows `.github/workflows/**`, config `security/**`, `monitoring/**`

## Exigences contributeurs

- Pas de secrets en clair (Gitleaks : `security/.gitleaks.toml`)
- Dépendances sans CVE critique non supprimée/justifiée (`security/owasp-suppressions.xml`)
- Image sans HIGH/CRITICAL non corrigé (Trivy)
- Migrations Flyway reproductibles (`src/main/resources/db/migration/V*.sql`)
- PR avec template `.github/pull_request_template.md` rempli

## Divulgation

Coordinated disclosure : patch d'abord, détails publics après déploiement du fix.
Merci de ne pas scanner aggressivement la prod sans autorisation.
