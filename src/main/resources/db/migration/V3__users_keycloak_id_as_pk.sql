-- A user's id is the Keycloak user id (sub claim): Keycloak gives it, we never generate one.
-- Rows that were never linked to a Keycloak account cannot log in any more: remove them.
DELETE FROM users WHERE keycloak_id IS NULL;

ALTER TABLE users DROP CONSTRAINT users_pkey;
ALTER TABLE users DROP COLUMN id;
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_keycloak_id_key;
ALTER TABLE users RENAME COLUMN keycloak_id TO id;
ALTER TABLE users ALTER COLUMN id SET NOT NULL;
ALTER TABLE users ADD PRIMARY KEY (id);
