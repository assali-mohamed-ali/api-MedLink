#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || {
  echo "Run this script from inside a cloned Git repository." >&2
  exit 2
}
cd "$ROOT"

if ! command -v pre-commit >/dev/null 2>&1; then
  cat >&2 <<'MSG'
pre-commit is not installed.

Recommended (pipx):
  sudo apt install pipx
  pipx ensurepath
  pipx install pre-commit

Then make sure ~/.local/bin is on PATH and run this script again.
MSG
  exit 127
fi

pre-commit validate-config .pre-commit-config.yaml
pre-commit install --install-hooks

echo
echo "Git pre-commit hook installed."
echo "Run all hooks once with: pre-commit run --all-files --show-diff-on-failure"
