package com.example.apimedlink.model;

/** How the audited action ended (MLink-59): refused, or done (MLink-26); ERROR for a failed operation worth recording. */
public enum AuditOutcome {
    DENIED,
    /** The action was carried out (MLink-104: a booking; MLink-31: a record read). */
    SUCCESS,
    /** A failed operation worth recording (epic MLink-9). */
    ERROR
}
