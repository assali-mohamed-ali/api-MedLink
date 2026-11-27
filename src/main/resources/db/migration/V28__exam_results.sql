-- US-13 / US-25: examination results a practitioner publishes for a patient they are attached to. Title, examination
-- type and content are sealed by the API (the reports' cipher) before they reach the database: never readable here.
-- A result may point at one of the patient's documents (the existing encrypted document store, downloaded by its own
-- route). read_at: when the patient first saw it (null: new for the patient).
CREATE TABLE exam_results (
    id                UUID        PRIMARY KEY,
    patient_id        UUID        NOT NULL REFERENCES patients (id),
    practitioner_id   UUID        NOT NULL REFERENCES practitioners (id),
    title_encrypted   TEXT        NOT NULL,
    type_encrypted    TEXT        NOT NULL,
    content_encrypted TEXT        NOT NULL,
    exam_date         DATE        NOT NULL,
    document_id       UUID        REFERENCES patient_documents (id),
    created_at        TIMESTAMPTZ NOT NULL,
    read_at           TIMESTAMPTZ
);
CREATE INDEX idx_exam_results_patient ON exam_results (patient_id, created_at);
CREATE INDEX idx_exam_results_practitioner_patient ON exam_results (practitioner_id, patient_id, created_at);
