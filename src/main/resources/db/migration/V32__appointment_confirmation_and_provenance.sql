-- US-08: existing appointments must not produce a replay of historic confirmation messages.
ALTER TABLE appointments ADD COLUMN confirmation_sent_at TIMESTAMPTZ;
UPDATE appointments SET confirmation_sent_at = created_at;
CREATE INDEX idx_appointments_confirmation_pending ON appointments (created_at)
    WHERE confirmation_sent_at IS NULL AND status = 'BOOKED';

-- US-30: persist provenance without returning the staff account id to the patient.
-- Historical assisted bookings have a non-patient actor distinct from the slot practitioner;
-- the existing absence-postponement path instead records the slot practitioner as actor.
ALTER TABLE appointments ADD COLUMN booked_by_staff BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE appointments a SET booked_by_staff = TRUE
FROM slots s WHERE s.id = a.slot_id AND a.booked_by <> a.patient_id AND a.booked_by <> s.practitioner_id;
