# medlink-snapshot: the vault host's daily raft snapshot (vault/snapshot.sh), nothing else (MLink-168).
# A snapshot is encrypted by Vault's barrier: it is useless without the unseal keys.
path "sys/storage/raft/snapshot" {
  capabilities = ["read"]
}
