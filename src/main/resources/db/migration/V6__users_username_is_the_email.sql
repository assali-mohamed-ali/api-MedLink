-- Patients log in with their email: the username Keycloak holds is the email, up to 254 characters.
ALTER TABLE users ALTER COLUMN username TYPE VARCHAR(254);
