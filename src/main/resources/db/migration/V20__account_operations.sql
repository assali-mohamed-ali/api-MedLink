-- MLink-72 (review of #122): the durable record of an account operation that reaches Keycloak (role change,
-- enable, disable). Keycloak is outside our transaction, so the operation is written here, with its ATTEMPTED audit
-- entry, in one transaction BEFORE anything is touched. It stays PENDING until a worker (or the request itself) has
-- carried it out and closed it, in one transaction with its closing audit entry. A crash after Keycloak but before
-- the close therefore leaves a PENDING row that is retried, never an orphan ATTEMPTED entry.
--
-- No foreign key on user_id: like audit_entries, a row must not depend on the user row it is about.
-- last_error holds an exception class name only, never a message.
CREATE TABLE account_operations (
    id              UUID         PRIMARY KEY,
    action          VARCHAR(50)  NOT NULL,
    user_id         UUID         NOT NULL,
    target_role     VARCHAR(50),
    specialty_id    UUID,
    clinic_ids      TEXT,
    enabled         BOOLEAN,
    requested_by    UUID,
    ip_address      VARCHAR(45),
    status          VARCHAR(10)  NOT NULL,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    locked_until    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    closed_at       TIMESTAMPTZ,
    last_error      VARCHAR(100),
    CONSTRAINT account_operations_status CHECK (status IN ('PENDING', 'DONE', 'FAILED'))
);

-- One operation at a time per account: a second, different request while one is pending is refused.
CREATE UNIQUE INDEX uq_account_operations_pending ON account_operations (user_id) WHERE status = 'PENDING';

-- What the worker looks for.
CREATE INDEX idx_account_operations_due ON account_operations (next_attempt_at) WHERE status = 'PENDING';

-- The correlation: the ATTEMPTED entry and its closing entry carry the same operation id. Adding a column does
-- not touch the rows, so the append-only triggers of V7 (UPDATE, DELETE, TRUNCATE) are not involved.
ALTER TABLE audit_entries ADD COLUMN operation_id UUID;

-- An operation is closed once: at most one SUCCESS or FAILURE entry per operation id.
CREATE UNIQUE INDEX uq_audit_entries_operation_closing ON audit_entries (operation_id)
    WHERE operation_id IS NOT NULL AND outcome IN ('SUCCESS', 'FAILURE');

CREATE INDEX idx_audit_entries_operation ON audit_entries (operation_id) WHERE operation_id IS NOT NULL;
