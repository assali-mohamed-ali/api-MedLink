-- US-29: the administrative staff propose to move a future appointment to another practitioner of the same clinic (for
-- instance when the clinic is taken off the first practitioner). The move happens only once the patient and the new
-- practitioner have both accepted. One open proposal per appointment.
CREATE TABLE appointment_reassignments (
    id                        UUID        PRIMARY KEY,
    appointment_id            UUID        NOT NULL REFERENCES appointments (id),
    target_slot_id            UUID        NOT NULL REFERENCES slots (id),
    proposed_by               UUID        REFERENCES users (id) ON DELETE SET NULL,
    status                    VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'DONE', 'DECLINED', 'FAILED')),
    practitioner_accepted_at  TIMESTAMPTZ,
    patient_accepted_at       TIMESTAMPTZ,
    created_at                TIMESTAMPTZ NOT NULL,
    closed_at                 TIMESTAMPTZ
);
CREATE UNIQUE INDEX appointment_reassignments_one_open
    ON appointment_reassignments (appointment_id) WHERE status = 'PENDING';
CREATE INDEX idx_reassignments_target_slot ON appointment_reassignments (target_slot_id);
