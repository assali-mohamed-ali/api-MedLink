# medlink-setup: what vault/setup.sh and vault/seed-env.sh need, nothing more (MLink-168).
# The repo owner creates a short-lived token with this policy only:
#   vault policy write medlink-setup vault/policies/medlink-setup.hcl
#   vault token create -policy=medlink-setup -ttl=8h -orphan
# Policy names are listed one by one (no glob), so this token cannot change its own policy.

# Read what is mounted, enabled and audited (the scripts only change what is missing)
path "sys/mounts" {
  capabilities = ["read"]
}
path "sys/auth" {
  capabilities = ["read"]
}
path "sys/audit" {
  capabilities = ["read", "sudo"]
}

# The KV v2 secrets engine at medlink/
path "sys/mounts/medlink" {
  capabilities = ["create", "update", "read"]
}

# GitHub Actions OIDC login at jwt-github/
path "sys/auth/jwt-github" {
  capabilities = ["create", "update", "read", "sudo"]
}
path "auth/jwt-github/config" {
  capabilities = ["create", "update", "read"]
}
path "auth/jwt-github/role/*" {
  capabilities = ["create", "update", "read", "list"]
}

# The policies the roles and the snapshot token use
path "sys/policies/acl/medlink-staging-api" {
  capabilities = ["create", "update", "read"]
}
path "sys/policies/acl/medlink-staging-front" {
  capabilities = ["create", "update", "read"]
}
path "sys/policies/acl/medlink-prod-api" {
  capabilities = ["create", "update", "read"]
}
path "sys/policies/acl/medlink-prod-front" {
  capabilities = ["create", "update", "read"]
}
path "sys/policies/acl/medlink-snapshot" {
  capabilities = ["create", "update", "read"]
}

# The file audit device
path "sys/audit/file" {
  capabilities = ["create", "update", "sudo"]
}

# The snapshot token role, and one token from it for the host's snapshot job
path "auth/token/roles/medlink-snapshot" {
  capabilities = ["create", "update", "read"]
}
path "auth/token/create/medlink-snapshot" {
  capabilities = ["update"]
}

# Seeding the environments' secrets (seed-env.sh adds missing keys, never overwrites)
path "medlink/data/*" {
  capabilities = ["create", "update", "patch", "read"]
}
path "medlink/metadata/*" {
  capabilities = ["read", "list"]
}
