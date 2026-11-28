-- US-22: a record number a practitioner can type ("MED-000042" on screen), one per patient, never reused. Existing
-- patients are numbered once here; later ones take the next value at insert. The number identifies a record, it does
-- not open it: search and record still require the practitioner's link to the patient.
CREATE SEQUENCE patient_record_number_seq START WITH 1;
ALTER TABLE patients ADD COLUMN record_number BIGINT;
UPDATE patients p
SET record_number = numbered.n
FROM (SELECT id, row_number() OVER (ORDER BY id) AS n FROM patients) numbered
WHERE p.id = numbered.id;
SELECT setval('patient_record_number_seq', COALESCE((SELECT max(record_number) FROM patients), 0) + 1, false);
ALTER TABLE patients ALTER COLUMN record_number SET DEFAULT nextval('patient_record_number_seq');
ALTER TABLE patients ALTER COLUMN record_number SET NOT NULL;
ALTER TABLE patients ADD CONSTRAINT uq_patients_record_number UNIQUE (record_number);
ALTER SEQUENCE patient_record_number_seq OWNED BY patients.record_number;

-- US-25: a publication carries the id the practitioner's form generated, so a repeated submission (double click,
-- retried request) returns the first result instead of creating a second one.
ALTER TABLE exam_results ADD COLUMN request_id UUID;
CREATE UNIQUE INDEX uq_exam_results_request ON exam_results (practitioner_id, request_id) WHERE request_id IS NOT NULL;

-- US-25: when the "new result" notice was sent (or settled without a mail). Null: still to send, after the publication
-- committed. Results published before this migration had no notice to send.
ALTER TABLE exam_results ADD COLUMN notice_sent_at TIMESTAMPTZ;
UPDATE exam_results SET notice_sent_at = created_at;
CREATE INDEX idx_exam_results_notice_pending ON exam_results (created_at) WHERE notice_sent_at IS NULL;
