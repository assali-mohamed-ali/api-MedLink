# Vault server configuration for the container (MLink-168). TLS ends on Caddy, on the same host.
ui            = false
disable_mlock = true

storage "raft" {
  path    = "/vault/file"
  node_id = "medlink-vault"
}

listener "tcp" {
  address         = "0.0.0.0:8200"
  cluster_address = "127.0.0.1:8201"
  tls_disable     = 1
}

cluster_addr = "http://127.0.0.1:8201"
