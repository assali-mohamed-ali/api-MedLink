-- Agenda (MLink-26 / 26.1). V13 (MLink-97) already created clinics, slots and appointments; this adds
-- what the agenda needs on top of them:
--   1. the three MedLink clinics, in every environment (no screen or endpoint creates a clinic, so a
--      portal started without them could not hold a single slot). Same ids as the dev seed
--      (db/dev-seed), whose insert skips them with ON CONFLICT, so dev keeps working.
--   2. a CLOSED slot status: a practitioner closes a slot instead of deleting it (a cancelled
--      appointment still references the slot). A closed slot keeps its time range, so the overlap
--      constraint of V13 still applies to it; the practitioner reopens it to free the time.

INSERT INTO clinics (id, name, city) VALUES
    ('c1000000-0000-4000-8000-000000000001', 'Clinique MedLink Sousse', 'Sousse'),
    ('c1000000-0000-4000-8000-000000000002', 'Clinique MedLink Sfax', 'Sfax'),
    ('c1000000-0000-4000-8000-000000000003', 'Clinique MedLink Tunis', 'Tunis')
ON CONFLICT (id) DO NOTHING;

ALTER TABLE slots DROP CONSTRAINT slots_status_check;
ALTER TABLE slots ADD CONSTRAINT slots_status_check CHECK (status IN ('FREE', 'BOOKED', 'CLOSED'));
