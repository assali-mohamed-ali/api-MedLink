-- MLink-62/63: the staff code (TOTP) of practitioners and staff. One row per user, written by the API only.
-- secret_enc: the secret sealed with AES-256-GCM (MfaSecretCipher, key from the environment), never in clear.
-- enabled: false from the enrolment until the first good code. last_used_step: the 30-second step of the last
-- accepted code, so a code works once. failed_attempts / locked_until: five wrong codes lock the code step
-- for 15 minutes, across logins. Every change is one statement (UserMfaStore): two requests at once never
-- lose a wrong code or use a code twice.
CREATE TABLE user_mfa (
    user_id         UUID        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    secret_enc      TEXT        NOT NULL,
    enabled         BOOLEAN     NOT NULL DEFAULT FALSE,
    enrolled_at     TIMESTAMPTZ,
    last_used_step  BIGINT,
    failed_attempts INTEGER     NOT NULL DEFAULT 0,
    locked_until    TIMESTAMPTZ
);
