# Contributing to MedLink API

## Install local pre-commit hooks

Prerequisites: Git, Python 3.9+, and a JDK 17-compatible development setup. The Maven project-model hook uses the repository's `./mvnw` wrapper.

Install the pre-commit CLI once (recommended on Ubuntu/Linux):

```bash
sudo apt install pipx
pipx ensurepath
pipx install pre-commit
export PATH="$HOME/.local/bin:$PATH"
```

From the repository root, install the Git hook and its pinned hook environments:

```bash
bash scripts/setup-pre-commit.sh
```

The installer validates `.pre-commit-config.yaml` and runs `pre-commit install --install-hooks`. After this, the checks run automatically on `git commit`.

## What runs before a commit?

- Basic hygiene: whitespace, final newline, YAML/JSON/XML/TOML validation, merge-conflict markers, case collisions, private-key detection, and a 1 MiB added-file limit.
- Gitleaks scans staged changes using `security/.gitleaks.toml`.
- Checkov checks changed supported infrastructure/configuration files with `security/checkov.yaml`.
- actionlint validates GitHub Actions workflow files.
- The Maven project model is validated when `pom.xml`, `mvnw`, or `.mvn/` files change.

Some hooks automatically fix whitespace/newline issues. If a hook changes files, stage the changes and retry the commit.

## Run the complete checks manually

```bash
pre-commit run --all-files --show-diff-on-failure --verbose
./mvnw --no-transfer-progress --fail-fast verify
bash scripts/ci/check-prod-config.sh
```

The full checks are also run in GitHub Actions. Pre-commit is a fast local feedback layer, not a replacement for CI. The Maven model validation and Checkov scan may take longer on first run while their environments/dependencies are downloaded.

## Updating hooks

Review version updates before changing pinned revisions in `.pre-commit-config.yaml`:

```bash
pre-commit autoupdate
pre-commit validate-config
pre-commit run --all-files
```
