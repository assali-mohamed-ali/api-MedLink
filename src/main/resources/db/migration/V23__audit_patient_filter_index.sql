-- MLink-17 / MLink-33: reading the trail by patient needs the patient's document ids and the patient id itself.
-- Append-only table: only read indexes are added, never rows changed or deleted.
CREATE INDEX idx_audit_entries_resource_id_timestamp
    ON audit_entries (resource_id, timestamp);
