# MedLink API DevSecOps lifecycle

## Release path

1. Pull requests run CI, CodeQL, secret scanning, dependency analysis, container/configuration scanning and the existing ZAP baseline workflow.
2. Release builds run Maven verification, build the runtime image once, scan that exact local image, generate a CycloneDX SBOM, and only then publish it to GHCR.
3. The workflow records the registry digest and creates a GitHub build-provenance attestation for that digest.
4. Staging/production deployment consumes the immutable image digest through the existing Docker Compose-over-SSH action. Kubernetes and Argo CD are intentionally not used.

## Required repository/environment configuration

- Enable GitHub Actions and GHCR package publishing.
- Configure staging and production environments with DEPLOY_HOST, DEPLOY_USER, DEPLOY_PATH, and DEPLOY_HEALTH_URL variables.
- Configure DEPLOY_SSH_KEY, DEPLOY_KNOWN_HOSTS, and DEPLOY_ENV_FILE as environment secrets.
- Require reviewers for the production environment; protect prod and release tags.
- Set CODE_SCANNING_ENABLED=true only when GitHub code scanning upload is available for this repository.
- Enable the GitHub dependency graph and set the repository variable DEPENDENCY_GRAPH_ENABLED=true to activate Dependency Review; the action is unsupported when the dependency graph/GitHub Advanced Security is unavailable.
- Configure NVD_API_KEY for the primary OWASP Dependency-Check path. Do not silently suppress failed scans.
- Keep exceptions scoped, ticketed, reviewed, and time-limited.

## Supply-chain controls

- Do not publish images from pull-request code.
- Use least-privilege job permissions and protect deployment credentials with GitHub Environments.
- Scan the same image artifact that is published; do not rebuild a different image after the scan.
- Deploy by digest, not a mutable latest tag.
- Keep SBOMs and scan reports as workflow artifacts for auditability.
- Each published image is signed with keyless Cosign. Staging and production jobs verify the signature against the expected GitHub Actions workflow identity before invoking Docker Compose. Provenance and SBOM attestations are attached to the same immutable image digest.

## Operational checks

- Confirm the deployed /actuator/health endpoint is healthy.
- Run staging integration and authenticated DAST tests before production promotion.
- Preserve the previous image digest and Compose configuration for rollback.
- Review scheduled scanner findings and Dependabot alerts; document any accepted risk.


## Local pre-commit hooks

Install and enable the repository hooks once:

```bash
pipx install pre-commit
pre-commit install
```

Run the entire configured hook set before opening a PR:

```bash
pre-commit run --all-files
```

The hooks check whitespace/newline hygiene, YAML/JSON/XML/TOML syntax, merge markers, private keys, large files, staged secrets with Gitleaks, IaC configuration with Checkov, workflow syntax with actionlint, and Maven project-model validation when Maven files change. CI runs the same `pre-commit run --all-files` check.

Gitleaks' local hook checks staged changes; the existing GitHub Secrets-Scan workflow remains responsible for repository-history scanning. Hooks are a fast local feedback layer, not a replacement for the complete CI security gates.
