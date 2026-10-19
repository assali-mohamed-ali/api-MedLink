-- GitHub #51: request and mail limits shared by every API instance. One row per counted subject (an IP on
-- an endpoint, an address for a kind of mail), keyed by "<kind>:" + SHA-256 of the subject, so no IP or
-- email is stored in clear. A hit is one atomic INSERT ... ON CONFLICT DO UPDATE (RateLimitStore): two
-- requests at once never read the same count. A row whose window has ended starts a new one at its next
-- hit; expired rows are deleted on a schedule.
CREATE TABLE rate_limits (
    key        VARCHAR(100) PRIMARY KEY,
    hits       INTEGER      NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_rate_limits_expires_at ON rate_limits (expires_at);
