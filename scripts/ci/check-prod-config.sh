#!/usr/bin/env bash
# Static check for GitHub #53: the Terraform app container must receive every
# setting the production profile needs, and no obsolete JWT secret.
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
    MAIL_HOST
    MAIL_PORT
    MAIL_USERNAME
    MAIL_PASSWORD
    MAIL_FROM
    APP_FRONTEND_URL
    CORS_ALLOWED_ORIGINS
)

env_keys="$(sed -n 's/.*"\([A-Z][A-Z0-9_]*\)=.*/\1/p' terraform/main.tf | sort -u)"
missing=0
for key in "${required[@]}"; do
    if ! grep -qx "$key" <<<"$env_keys"; then
        echo "::error::terraform/main.tf does not set ${key}, required by the production profile" >&2
        missing=1
    fi
done

obsolete="$(grep -RIn --include='*.tf' --include='*.tfvars' -E 'JWT_SECRET|jwt_secret' terraform | grep -vE '^[^:]+:[0-9]+:[[:space:]]*#' || true)"
if [ -n "$obsolete" ]; then
    echo "::error::Terraform still references the obsolete JWT secret:" >&2
    printf '%s\n' "$obsolete" >&2
    missing=1
fi

if [ "$missing" -ne 0 ]; then
    exit 1
fi

echo 'Terraform production environment: all required settings present, no JWT secret.'
