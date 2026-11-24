#!/bin/sh
# Vault configuration as code for the MedLink deploys (MLink-168). Safe to run again: it only adds what is missing
# and rewrites the MedLink policies and roles to what this repository says.
#
#   VAULT_ADDR=http://127.0.0.1:8200 VAULT_TOKEN=<token with policy medlink-setup> sh vault/setup.sh
#
# Run it on the vault host (Vault listens on 127.0.0.1 only). It sets up:
#   - a KV v2 secrets engine at medlink/, one path per environment and application: medlink/<staging|prod>/<api|front>
#   - GitHub Actions OIDC login at jwt-github/: role <app>-<staging|production> accepts only a token issued for
#     repository <repo> and GitHub environment <env> (claim sub), audience medlink-vault; read-only on its own path;
#     Vault tokens of 10 minutes
#   - the file audit device (AUDIT_FILE, default /var/log/vault/audit.log: create the directory first, owned by vault)
#   - the token role medlink-snapshot for the host's daily snapshot
# It never reads or prints a secret value. Runbook: docs/operations/vault.md.
set -eu

: "${VAULT_ADDR:?VAULT_ADDR must be set (http://127.0.0.1:8200 on the vault host)}"
: "${VAULT_TOKEN:?VAULT_TOKEN must be set: a token with the medlink-setup policy}"
export VAULT_ADDR VAULT_TOKEN
HERE="$(cd "$(dirname "$0")" && pwd -P)"
AUDIT_FILE="${AUDIT_FILE:-/var/log/vault/audit.log}"
OWNER=assali-mohamed-ali
AUDIENCE=medlink-vault
GITHUB_ISSUER=https://token.actions.githubusercontent.com

say() { echo "vault-setup: $*"; }
die() { echo "vault-setup: $*" >&2; exit 1; }

vault status > /dev/null 2>&1 || die "Vault at $VAULT_ADDR is not reachable or is sealed (unseal it first)"

# 1. Secrets engine
if vault secrets list | grep -q '^medlink/ '; then
    vault read -field=options sys/mounts/medlink | grep -q 'version:2' ||
        die "medlink/ is mounted but is not KV version 2: fix it by hand, nothing was changed"
    say "medlink/ (KV v2) already enabled"
else
    vault secrets enable -path=medlink -version=2 -description="MedLink deploy secrets (vault/setup.sh)" kv > /dev/null
    say "medlink/ (KV v2) enabled"
fi

# 2. Policies, from the files in this repository
for name in medlink-staging-api medlink-staging-front medlink-prod-api medlink-prod-front medlink-snapshot; do
    vault policy write "$name" "$HERE/policies/$name.hcl" > /dev/null
done
say "policies written: medlink-{staging,prod}-{api,front}, medlink-snapshot"

# 3. GitHub Actions OIDC login
if vault auth list | grep -q '^jwt-github/ '; then
    say "jwt-github/ already enabled"
else
    vault auth enable -path=jwt-github -description="GitHub Actions OIDC (vault/setup.sh)" jwt > /dev/null
    say "jwt-github/ enabled"
fi
vault write auth/jwt-github/config oidc_discovery_url="$GITHUB_ISSUER" bound_issuer="$GITHUB_ISSUER" > /dev/null
say "jwt-github/ trusts $GITHUB_ISSUER"

# $1 role, $2 repository, $3 GitHub environment, $4 policy, $5 deploy workflow file, $6... refs it may deploy from.
# The environment alone is not enough: any workflow of the repository that enters it would get the same subject. So the
# token must also come from the deploy workflow (workflow_ref), on one of the refs (ref, globs), by a push or a manual
# run (event_name): a pull_request job, another workflow or a feature branch is refused even inside the environment.
# GitHub's immutable subject claims add the repository ids (repo:owner@id/repo@id:environment:env); accept both forms.
role() {
    local name="$1" repo="$2" env="$3" policy="$4" workflow="$5"; shift 5
    local refs="" wrefs="" r
    for r in "$@"; do
        refs="$refs${refs:+, }\"$r\""
        wrefs="$wrefs${wrefs:+, }\"$OWNER/$repo/.github/workflows/$workflow@$r\""
    done
    vault write "auth/jwt-github/role/$name" - > /dev/null <<EOF
{
  "role_type": "jwt",
  "user_claim": "sub",
  "bound_audiences": ["$AUDIENCE"],
  "bound_claims_type": "glob",
  "bound_claims": {
    "sub": ["repo:$OWNER/$repo:environment:$env", "repo:$OWNER@*/$repo@*:environment:$env"],
    "workflow_ref": [$wrefs],
    "ref": [$refs],
    "event_name": ["push", "workflow_dispatch"]
  },
  "claim_mappings": { "repository": "repository", "environment": "environment", "workflow_ref": "workflow_ref", "actor": "actor", "run_id": "run_id" },
  "token_policies": ["$policy"],
  "token_ttl": "10m",
  "token_max_ttl": "15m",
  "token_type": "service"
}
EOF
    say "role $name: repo:$OWNER/$repo:environment:$env (old or immutable sub), $workflow on $* -> $policy (read-only, 10 min)"
}
role api-staging      api-MedLink       staging    medlink-staging-api   cd.yml refs/heads/staging refs/heads/master refs/heads/main
role api-production   api-MedLink       production medlink-prod-api      cd.yml refs/heads/staging 'refs/tags/v*'
role front-staging    medlink-dashboard staging    medlink-staging-front container-security.yml refs/heads/staging refs/heads/master refs/heads/main
role front-production medlink-dashboard production medlink-prod-front    container-security.yml refs/heads/staging refs/heads/prod 'refs/tags/v*'

# 4. Audit log: every request recorded, secret values only as HMACs
if vault audit list 2> /dev/null | grep -q '^file/ '; then
    say "file audit device already enabled"
else
    vault audit enable file file_path="$AUDIT_FILE" > /dev/null ||
        die "could not enable the audit log at $AUDIT_FILE: create its directory, owned by the vault user"
    say "file audit device enabled: $AUDIT_FILE"
fi

# 5. Token role for the daily snapshot (the token itself is created on the host: vault/install-host.sh)
vault write auth/token/roles/medlink-snapshot allowed_policies=medlink-snapshot orphan=true renewable=true \
    token_period=768h token_type=service > /dev/null
say "token role medlink-snapshot written"
say "done"
