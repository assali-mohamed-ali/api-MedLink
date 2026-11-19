# medlink-prod-front: what the front deploy to prod reads, nothing else (MLink-168).
# Given by the GitHub OIDC role front-production (vault/setup.sh): read-only, no list, no other path.
path "medlink/data/prod/front" {
  capabilities = ["read"]
}
