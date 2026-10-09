# Politique de sécurité — api-MedLink

## Versions supportées

| Version | Support |
|---------|---------|
| `main` (dernière) | :white_check_mark: |
| tags `v*` maintenus 3 mois | :white_check_mark: |
| autres branches | :x: |

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
