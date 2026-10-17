-- MLink-59: the audit trail (AuditEntry in the class diagram). Append-only: the application inserts
-- and reads, it never changes or deletes a row (MLink-17), and the database enforces it (triggers below).
CREATE TABLE audit_entries (
    id            UUID         PRIMARY KEY,
    timestamp     TIMESTAMPTZ  NOT NULL,
    -- The caller's Keycloak id (= users.id), null for an anonymous caller. No foreign key on purpose:
    -- an audit row must outlive the user it is about.
    user_id       UUID,
    action        VARCHAR(50)  NOT NULL,
    resource_type VARCHAR(50)  NOT NULL,
    resource_id   VARCHAR(255) NOT NULL,
    ip_address    VARCHAR(45),
    outcome       VARCHAR(20)  NOT NULL
);

CREATE INDEX idx_audit_entries_user_timestamp ON audit_entries (user_id, timestamp);

CREATE FUNCTION audit_entries_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_entries is append-only: % refused', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_entries_no_update_or_delete
    BEFORE UPDATE OR DELETE ON audit_entries
    FOR EACH ROW EXECUTE FUNCTION audit_entries_append_only();

CREATE TRIGGER audit_entries_no_truncate
    BEFORE TRUNCATE ON audit_entries
    FOR EACH STATEMENT EXECUTE FUNCTION audit_entries_append_only();
