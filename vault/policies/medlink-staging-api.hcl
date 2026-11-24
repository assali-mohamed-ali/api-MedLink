# medlink-staging-api: what the api deploy to staging reads, nothing else (MLink-168).
# Given by the GitHub OIDC role api-staging (vault/setup.sh): read-only, no list, no other path.
path "medlink/data/staging/api" {
  capabilities = ["read"]
}
