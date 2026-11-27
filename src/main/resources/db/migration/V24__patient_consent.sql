-- US-04: the consent a patient gave at sign-up: which text (its version) and when. Nullable: patients registered
-- before this migration, or in an environment where the consent is not required, have none.
ALTER TABLE patients ADD COLUMN consent_version VARCHAR(30);
ALTER TABLE patients ADD COLUMN consented_at TIMESTAMPTZ;
