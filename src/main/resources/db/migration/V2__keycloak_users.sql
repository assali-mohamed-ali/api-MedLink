-- Keycloak owns credentials and roles: the backend keeps no password and no role.
ALTER TABLE users DROP COLUMN IF EXISTS password;
ALTER TABLE users DROP COLUMN IF EXISTS role;

-- Link to the Keycloak account (sub claim of the ID token).
ALTER TABLE users ADD COLUMN IF NOT EXISTS keycloak_id UUID UNIQUE;
