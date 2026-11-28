-- US-21: a practitioner declares an absence (a period when they cannot see patients). Declaring it closes the free slots
-- it covers; the booked appointments it covers wait for the practitioner's decision (cancel, or move to next week).
CREATE TABLE practitioner_absences (
    id              UUID         PRIMARY KEY,
    practitioner_id UUID         NOT NULL REFERENCES practitioners (id) ON DELETE CASCADE,
    starts_at       TIMESTAMPTZ  NOT NULL,
    ends_at         TIMESTAMPTZ  NOT NULL,
    reason          VARCHAR(200),
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT practitioner_absences_period CHECK (ends_at > starts_at)
);
CREATE INDEX idx_absences_practitioner_period ON practitioner_absences (practitioner_id, starts_at, ends_at);
