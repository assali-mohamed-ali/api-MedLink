-- US-03: a new purpose of the mailed-link tokens: the "forgot my password" link.
ALTER TABLE email_tokens DROP CONSTRAINT email_tokens_purpose_check;
ALTER TABLE email_tokens ADD CONSTRAINT email_tokens_purpose_check
    CHECK (purpose IN ('VERIFY_EMAIL', 'ACTIVATE_ACCOUNT', 'MFA_ENROLMENT', 'PASSWORD_RESET'));
