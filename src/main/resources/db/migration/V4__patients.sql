-- A patient is a user with a profile: same id as users.id (the Keycloak id), no second id.
-- Personal fields are plain for now; encryption at rest comes with MLink-14 / MLink-24.
CREATE TABLE patients (
    id UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    birth_date DATE NOT NULL,
    phone VARCHAR(30),
    address VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
