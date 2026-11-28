-- US-29: preserve foreign-key targets of historical appointments while retiring clinic assignments.
ALTER TABLE practitioner_clinics ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
