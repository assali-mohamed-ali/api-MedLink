package com.example.apimedlink.model;

/**
 * What an audit entry records (MLink-59). Other tasks add their values (MLink-72: account operations;
 * MLink-26: the practitioner's slots).
 */
public enum AuditAction {
    ACCESS_DENIED,
    SLOT_OPENED,
    SLOT_CLOSED,
    /** A practitioner read a patient's record (MLink-31): ids only, never medical content. */
    RECORD_VIEWED,
    /** A practitioner wrote a consultation report (MLink-32): the report id only, never its text. */
    REPORT_CREATED,
    /** A practitioner read a patient's consultation reports (MLink-32): the patient id only. */
    REPORT_VIEWED,
    /** A staff account was created (MLink-72). */
    ACCOUNT_CREATED,
    /** A staff account changed role (MLink-72). */
    ROLE_CHANGED,
    /** A staff account was enabled (MLink-72). */
    ACCOUNT_ENABLED,
    /** A staff account was disabled (MLink-72). */
    ACCOUNT_DISABLED
}
