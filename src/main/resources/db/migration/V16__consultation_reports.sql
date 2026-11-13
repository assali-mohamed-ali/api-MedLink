-- MLink-32: consultation reports, attached to the patient record. The content is AES-256-GCM sealed by the API
-- (ReportCipher, key APP_REPORT_ENCRYPTION_KEY): there is no plain text column. A report is written once by a
-- practitioner and never changed or deleted: a correction is a new report (decision A3, like audit_entries).

CREATE TABLE consultation_reports (
    id                UUID        PRIMARY KEY,
    patient_id        UUID        NOT NULL REFERENCES patients (id),
    -- Only practitioners write reports (decision A1): the database holds the rule
    practitioner_id   UUID        NOT NULL REFERENCES practitioners (id),
    -- The consultation the report is about; not exposed by the API yet (decision A5)
    appointment_id    UUID        REFERENCES appointments (id),
    content_encrypted TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A patient's reports, newest first (MLink-32 list, MLink-31 record)
CREATE INDEX idx_consultation_reports_patient_created ON consultation_reports (patient_id, created_at);

CREATE FUNCTION consultation_reports_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'consultation_reports are immutable: % refused (a correction is a new report)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER consultation_reports_no_update_or_delete
    BEFORE UPDATE OR DELETE ON consultation_reports
    FOR EACH ROW EXECUTE FUNCTION consultation_reports_immutable();
