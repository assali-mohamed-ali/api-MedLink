-- MLink-37: the practitioner's alerts (a patient cancelled an appointment). Kept until read, so a practitioner who was
-- offline still finds them at the next login (H-06). V17: V15 is the agenda (MLink-26) and V16 the consultation
-- reports (MLink-32), so this migration merges after both.

CREATE TABLE alerts (
    id              UUID         PRIMARY KEY,
    -- An alert is for a practitioner (practitioners.id = users.id): the database holds the rule
    practitioner_id UUID         NOT NULL REFERENCES practitioners (id),
    type            VARCHAR(40)  NOT NULL CHECK (type IN ('APPOINTMENT_CANCELLED')),
    -- The appointment the alert is about (a cancellation always has one)
    appointment_id  UUID         REFERENCES appointments (id),
    -- Written by the API in French, shown as it is: names, clinic, date and time, never medical content
    message         VARCHAR(500) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Null until the practitioner marks it read
    read_at         TIMESTAMPTZ,
    -- One cancellation, one alert, even if the cancellation is processed twice
    CONSTRAINT uq_alerts_type_appointment UNIQUE (type, appointment_id)
);

-- The unread alerts of a practitioner, newest first (the bell, every minute)
CREATE INDEX idx_alerts_unread ON alerts (practitioner_id, created_at DESC) WHERE read_at IS NULL;
