# medlink-prod-api: what the api deploy to prod reads, nothing else (MLink-168).
# Given by the GitHub OIDC role api-production (vault/setup.sh): read-only, no list, no other path.
path "medlink/data/prod/api" {
  capabilities = ["read"]
}
