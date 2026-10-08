## Description

<!-- Décrivez le changement, le contexte et le ticket lié -->

## Type de changement

- [ ] feat
- [ ] fix
- [ ] chore/docs/refacto
- [ ] security fix

## Checklist DevSecOps

- [ ] Tests unitaires/intégration ajoutés ou mis à jour (`./mvnw verify` OK en local)
- [ ] Pas de secrets ajoutés (vérifié avec `gitleaks detect --config security/.gitleaks.toml`)
- [ ] Analyse dépendances OK (pas de CVE critique ignorée sans suppression justifiée dans `security/owasp-suppressions.xml`)
- [ ] Dockerfile / compose / terraform validés si modifiés (`docker build`, `terraform validate`)
- [ ] Endpoints exposés documentés (OpenAPI) et couverts par les règles ZAP si API publique modifiée
- [ ] Migration Flyway testée en montée ET descente si schéma modifié (`src/main/resources/db/migration/`)

## Preuves

<!-- logs, captures, lien vers run CI -->
