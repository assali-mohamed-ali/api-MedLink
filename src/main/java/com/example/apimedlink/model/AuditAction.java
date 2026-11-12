package com.example.apimedlink.model;

/** What an audit entry records (MLink-59). Other tasks add their values (MLink-72: account operations). */
public enum AuditAction {
    ACCESS_DENIED,
    /** A practitioner read a patient's record (MLink-31): ids only, never medical content. */
    RECORD_VIEWED,
    /** A practitioner wrote a consultation report (MLink-32): the report id only, never its text. */
    REPORT_CREATED,
    /** A practitioner read a patient's consultation reports (MLink-32): the patient id only. */
    REPORT_VIEWED
}
