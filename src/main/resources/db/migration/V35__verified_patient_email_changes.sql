-- A verified change is durable until both Keycloak and the local user row agree.
CREATE TABLE patient_email_changes (
    patient_id UUID PRIMARY KEY REFERENCES patients(id) ON DELETE CASCADE,
    old_email VARCHAR(254) NOT NULL,
    new_email VARCHAR(254) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ,
    CHECK (old_email <> new_email)
);
CREATE INDEX idx_patient_email_change_pending ON patient_email_changes(verified_at)
    WHERE verified_at IS NOT NULL;
