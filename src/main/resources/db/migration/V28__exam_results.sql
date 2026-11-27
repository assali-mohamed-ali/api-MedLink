-- US-13 / US-25: examination results a practitioner publishes for a patient they are attached to. Title and content are
-- sealed by the API (the reports' cipher) before they reach the database: never readable here.
CREATE TABLE exam_results (
    id                UUID        PRIMARY KEY,
    patient_id        UUID        NOT NULL REFERENCES patients (id),
    practitioner_id   UUID        NOT NULL REFERENCES practitioners (id),
    title_encrypted   TEXT        NOT NULL,
    content_encrypted TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_exam_results_patient ON exam_results (patient_id, created_at);
