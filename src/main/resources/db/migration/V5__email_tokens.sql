-- Links we mail (verify an email, activate an account). Only the SHA-256 of the token is stored.
CREATE TABLE email_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose VARCHAR(20) NOT NULL CHECK (purpose IN ('VERIFY_EMAIL', 'ACTIVATE_ACCOUNT')),
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_email_tokens_user_purpose ON email_tokens (user_id, purpose);
