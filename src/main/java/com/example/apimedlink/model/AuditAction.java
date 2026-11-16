package com.example.apimedlink.model;

/**
 * What an audit entry records (MLink-59). Other tasks add their values (MLink-72: account operations;
 * MLink-26: the practitioner's slots; MLink-114: sign-ins and account changes).
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
    /** A sign-in attempt; the outcome says whether it worked (MLink-114). */
    LOGIN,
    /** The password is right but must be changed first: no session exists yet (MLink-114). */
    PASSWORD_CHANGE_REQUIRED,
    /** An account was modified (MLink-114). */
    ACCOUNT_UPDATED,
    /** A user read sensitive data; the resource type says which (MLink-114). */
    DATA_READ
}
