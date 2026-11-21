-- GitHub #57 (MLink-165): two database roles. Flyway runs as the owner of the schema (POSTGRES_USER); the API
-- connects as medlink_app, which can use the tables but not change them: no ownership, so no ALTER, DROP,
-- TRUNCATE or TRIGGER, and no CREATE in the schema. On the audit trail it may only append and read, so even
-- a compromised application (an SQL injection) cannot rewrite it; the V7 triggers stay as a second wall.
--
-- The role itself, with its password, comes from docker/postgres/initdb/10-app-role.sh. It is created here
-- too, without login, when it is missing (a database made before #57, e.g. an old dev volume), so that this
-- migration never fails there; the setup scripts then give it its login and password.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'medlink_app') THEN
        CREATE ROLE medlink_app NOLOGIN;
    END IF;
END
$$;

-- Nobody but the owner creates objects in the schema (already the default since PostgreSQL 15).
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO medlink_app;

-- Every table so far, then the exceptions.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO medlink_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO medlink_app;

-- The audit trail: append and read only.
REVOKE UPDATE, DELETE ON audit_entries FROM medlink_app;

-- Flyway's own history belongs to the migrations, not to the application.
REVOKE ALL ON flyway_schema_history FROM medlink_app;

-- Tables and sequences of later migrations (created by the owner, who runs this) are granted the same way.
-- A later append-only table needs its own REVOKE, like audit_entries above.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO medlink_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO medlink_app;
