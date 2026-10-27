package com.example.apimedlink.model;

/** What an audit entry records (MLink-59). Other tasks add their values. */
public enum AuditAction {
    ACCESS_DENIED,
    /** A sign-in attempt; the outcome says whether it worked (MLink-114). */
    LOGIN,
    /** An account was modified (MLink-114). */
    ACCOUNT_UPDATED,
    /** A user read sensitive data; the resource type says which (MLink-114). */
    DATA_READ
}
