-- US-15: the patient's notification preferences. Both default to true, so every existing patient keeps receiving what
-- they received before this migration. The booking confirmation is the receipt of an action the patient (or the
-- clinic on their behalf) just took and is not optional; only the reminder and the "new result" notice are.
ALTER TABLE patients
    ADD COLUMN notify_appointment_reminders BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN notify_exam_results          BOOLEAN NOT NULL DEFAULT TRUE;

-- US-16: an approved deletion request closes the account (Keycloak disabled, sessions revoked). The time of that closure
-- is kept apart from the decision: the decision is the staff's, the closure is the durable account operation that
-- follows it. Anonymisation and purge are irreversible and stay out of scope until the retention policy is approved.
ALTER TABLE deletion_requests
    ADD COLUMN account_closed_at TIMESTAMPTZ;
