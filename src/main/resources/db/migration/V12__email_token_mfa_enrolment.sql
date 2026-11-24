-- MLink-62 (review of #97): a practitioner's or staff member's first staff-code setup needs a link mailed to them,
-- so a stolen password alone cannot enrol a code. New purpose of the existing mailed-link tokens.
ALTER TABLE email_tokens DROP CONSTRAINT email_tokens_purpose_check;
ALTER TABLE email_tokens ADD CONSTRAINT email_tokens_purpose_check
    CHECK (purpose IN ('VERIFY_EMAIL', 'ACTIVATE_ACCOUNT', 'MFA_ENROLMENT'));
