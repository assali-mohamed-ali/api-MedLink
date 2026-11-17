#!/usr/bin/env bash
# GitHub #57 (MLink-165): the API's runtime role. Flyway migrates as the owner (POSTGRES_USER); the API connects
# as medlink_app, which may use the tables but never own or change them (grants: V18__app_role_grants.sql).
#
# The one place that creates the role, used three ways:
#   - by the postgres image, from /docker-entrypoint-initdb.d, when its volume is created (compose.yaml,
#     docker-compose.yml, and the Testcontainers container of the tests);
#   - by the setup scripts on an existing volume: docker compose exec -T postgres bash <this file>;
#   - by hand on a server whose volume predates the role (same command).
# Safe to run again: creates the role when missing, then (re)sets its login, attributes and password.
# APP_DB_PASSWORD comes from the container's environment and is never printed.
set -eu

: "${APP_DB_PASSWORD:?APP_DB_PASSWORD must be set (the password of the medlink_app role)}"

psql -v ON_ERROR_STOP=1 -q --username "$POSTGRES_USER" --dbname "${POSTGRES_DB:-$POSTGRES_USER}" \
    -v app_password="$APP_DB_PASSWORD" <<'SQL'
SELECT 'CREATE ROLE medlink_app' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'medlink_app')\gexec
ALTER ROLE medlink_app WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    PASSWORD :'app_password';
SQL
