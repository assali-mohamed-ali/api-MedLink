-- US-16: a patient's request to delete their data, and the decision of the administrative staff. Only the request and its
-- decision are stored here; nothing is deleted by them. The irreversible purge of an APPROVED request is not implemented
-- yet (docs/product-decisions.md). One request per patient; it disappears with the patient.
CREATE TABLE deletion_requests (
    patient_id    UUID         PRIMARY KEY REFERENCES patients (id) ON DELETE CASCADE,
    requested_at  TIMESTAMPTZ  NOT NULL,
    status        VARCHAR(10)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    decided_by    UUID,
    decided_at    TIMESTAMPTZ,
    decision_note VARCHAR(500)
);
