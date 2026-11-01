#!/usr/bin/env bash
# Static check for GitHub #53 and MLink-9: the production container and the full-stack compose
# deployment must receive every setting the production profile needs, and no obsolete JWT secret.
set -euo pipefail

required=(
    SPRING_PROFILES_ACTIVE
    SPRING_DATASOURCE_URL
    SPRING_DATASOURCE_USERNAME
    SPRING_DATASOURCE_PASSWORD
    KEYCLOAK_PUBLIC_URL
    KEYCLOAK_INTERNAL_URL
    KEYCLOAK_REALM
    KEYCLOAK_CLIENT_ID
    KEYCLOAK_CLIENT_SECRET
    KEYCLOAK_ADMIN_CLIENT_ID
    KEYCLOAK_ADMIN_CLIENT_SECRET
    APP_MFA_ENCRYPTION_KEY
    APP_DOCUMENTS_ENCRYPTION_KEY
    APP_DOCUMENTS_STORAGE_ENDPOINT
    APP_DOCUMENTS_STORAGE_ACCESS_KEY
    APP_DOCUMENTS_STORAGE_SECRET_KEY
    APP_DOCUMENTS_STORAGE_BUCKET
    APP_DOCUMENTS_STORAGE_CREATE_BUCKET
    APP_DOCUMENTS_STORAGE_REQUIRE_TLS
    MAIL_HOST
    MAIL_PORT
    MAIL_USERNAME
    MAIL_PASSWORD
    MAIL_FROM
    APP_FRONTEND_URL
    CORS_ALLOWED_ORIGINS
)

missing=0
env_keys="$(sed -n 's/.*"\([A-Z][A-Z0-9_]*\)=.*/\1/p' terraform/main.tf | sort -u)"
for key in "${required[@]}"; do
    if ! grep -qx "$key" <<<"$env_keys"; then
        echo "::error::terraform/main.tf does not set ${key}, required by the production profile" >&2
        missing=1
    fi
done

# The full-stack CD/self-hosted topology (docker-compose.yml) needs the same document settings.
# MinIO root credentials have no default there: an empty or implicit password must never reach a host.
compose_required=(
    MINIO_ROOT_USER
    MINIO_ROOT_PASSWORD
    APP_DOCUMENTS_ENCRYPTION_KEY
    APP_DOCUMENTS_STORAGE_ENDPOINT
    APP_DOCUMENTS_STORAGE_ACCESS_KEY
    APP_DOCUMENTS_STORAGE_SECRET_KEY
    APP_DOCUMENTS_STORAGE_BUCKET
    APP_DOCUMENTS_STORAGE_CREATE_BUCKET
    APP_DOCUMENTS_STORAGE_REQUIRE_TLS
)
compose_keys="$(sed -n 's/^[[:space:]]*\([A-Z][A-Z0-9_]*\):.*/\1/p' docker-compose.yml | sort -u)"
for key in "${compose_required[@]}"; do
    if ! grep -qx "$key" <<<"$compose_keys"; then
        echo "::error::docker-compose.yml does not set ${key}, required by the full-stack deployment" >&2
        missing=1
    fi
done

for key in MINIO_ROOT_USER MINIO_ROOT_PASSWORD APP_DOCUMENTS_ENCRYPTION_KEY; do
    if ! grep -Eq "^[[:space:]]*${key}:[[:space:]]*\\$\\{${key}:" docker-compose.yml; then
        echo "::error::docker-compose.yml must require ${key} with \${${key}:?message}, not a default value" >&2
        missing=1
    fi
done

# The document key comes from a real secret, never a placeholder left in the container definition.
documents_key="$(sed -n 's/.*"APP_DOCUMENTS_ENCRYPTION_KEY=\(.*\)".*/\1/p' terraform/main.tf | head -n 1)"
case "$documents_key" in
    '' | *change-me* | *CHANGE-ME* | *placeholder* | *example*)
        echo "::error::terraform/main.tf does not pass a real APP_DOCUMENTS_ENCRYPTION_KEY (missing or placeholder)" >&2
        missing=1
        ;;
esac

# Outside the local compose stack the object store is HTTPS-only.
if ! grep -Fq '"APP_DOCUMENTS_STORAGE_REQUIRE_TLS=true"' terraform/main.tf; then
    echo "::error::terraform/main.tf must set APP_DOCUMENTS_STORAGE_REQUIRE_TLS=true" >&2
    missing=1
fi

obsolete="$(grep -RIn --include='*.tf' --include='*.tfvars' -E 'JWT_SECRET|jwt_secret' terraform | grep -vE '^[^:]+:[0-9]+:[[:space:]]*#' || true)"
if [ -n "$obsolete" ]; then
    echo "::error::Terraform still references the obsolete JWT secret:" >&2
    printf '%s\n' "$obsolete" >&2
    missing=1
fi

if [ "$missing" -ne 0 ]; then
    exit 1
fi

echo 'Production configuration: all required settings present, no JWT secret.'
