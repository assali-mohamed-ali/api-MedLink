# medlink-staging-front: what the front deploy to staging reads, nothing else (MLink-168).
# Given by the GitHub OIDC role front-staging (vault/setup.sh): read-only, no list, no other path.
path "medlink/data/staging/front" {
  capabilities = ["read"]
}
