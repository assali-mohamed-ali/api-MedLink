-- US-16: a patient's request to delete their data. Only the request is stored here; nothing is deleted by it. The
-- irreversible purge is not implemented until the retention period of medical data is confirmed
-- (docs/product-decisions.md). One request per patient; it disappears with the patient.
CREATE TABLE deletion_requests (
    patient_id   UUID        PRIMARY KEY REFERENCES patients (id) ON DELETE CASCADE,
    requested_at TIMESTAMPTZ NOT NULL
);
