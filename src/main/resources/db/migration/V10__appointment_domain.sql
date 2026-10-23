-- MLink-97: the appointment domain (epic MLink-8). Clinics, specialties, practitioners and their
-- clinics, bookable slots, and the appointments patients book on them. Times are timestamptz (UTC).

CREATE TABLE clinics (
    id   UUID         PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    city VARCHAR(100) NOT NULL
);

CREATE TABLE specialties (
    id   UUID         PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE
);

-- A practitioner is a user with one role only (JOINED, like patients): same id as users.id, the Keycloak id.
-- The names live here, as for patients: the search shows them without asking Keycloak.
CREATE TABLE practitioners (
    id           UUID         PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    first_name   VARCHAR(100) NOT NULL,
    last_name    VARCHAR(100) NOT NULL,
    specialty_id UUID         NOT NULL REFERENCES specialties (id)
);

CREATE INDEX idx_practitioners_specialty ON practitioners (specialty_id);

CREATE TABLE practitioner_clinics (
    practitioner_id UUID NOT NULL REFERENCES practitioners (id) ON DELETE CASCADE,
    clinic_id       UUID NOT NULL REFERENCES clinics (id),
    PRIMARY KEY (practitioner_id, clinic_id)
);

-- Lets a GiST index hold the practitioner id (equality) next to the time range (overlap), for slots_no_overlap.
-- A trusted extension: the database owner may create it.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- One practitioner is in one place at a time: two of their slots never overlap, in any clinic (review on PR #87).
-- Ranges are [start, end): a slot ending at 09:30 and one starting at 09:30 are adjacent, not overlapping.
-- The unique start time stays: it indexes the practitioner's agenda and is the dev seed's ON CONFLICT target.
CREATE TABLE slots (
    id              UUID        PRIMARY KEY,
    practitioner_id UUID        NOT NULL REFERENCES practitioners (id),
    clinic_id       UUID        NOT NULL REFERENCES clinics (id),
    starts_at       TIMESTAMPTZ NOT NULL,
    ends_at         TIMESTAMPTZ NOT NULL,
    status          VARCHAR(10) NOT NULL DEFAULT 'FREE',
    CONSTRAINT slots_practitioner_starts_at_unique UNIQUE (practitioner_id, starts_at),
    CONSTRAINT slots_no_overlap EXCLUDE USING gist
        (practitioner_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&),
    CONSTRAINT slots_ends_after_start CHECK (ends_at > starts_at),
    CONSTRAINT slots_status_check CHECK (status IN ('FREE', 'BOOKED'))
);

CREATE INDEX idx_slots_clinic ON slots (clinic_id);

-- patient_id: who holds the appointment (the owner checked on cancel and reschedule).
-- booked_by: who made the booking (the patient today; staff could book for a patient later).
CREATE TABLE appointments (
    id               UUID        PRIMARY KEY,
    slot_id          UUID        NOT NULL REFERENCES slots (id),
    patient_id       UUID        NOT NULL REFERENCES patients (id),
    booked_by        UUID        NOT NULL REFERENCES users (id),
    status           VARCHAR(10) NOT NULL DEFAULT 'BOOKED',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at     TIMESTAMPTZ,
    reminder_sent_at TIMESTAMPTZ,
    CONSTRAINT appointments_status_check CHECK (status IN ('BOOKED', 'CANCELLED'))
);

CREATE INDEX idx_appointments_patient ON appointments (patient_id);
CREATE INDEX idx_appointments_slot ON appointments (slot_id);

-- A slot holds one active appointment at most, even when two bookings race (review on PR #87).
-- A cancelled appointment does not count, so its slot can be booked again.
CREATE UNIQUE INDEX appointments_one_active_per_slot ON appointments (slot_id) WHERE status = 'BOOKED';
