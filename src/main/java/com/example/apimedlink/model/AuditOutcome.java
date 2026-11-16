package com.example.apimedlink.model;

/** How the audited action ended (MLink-59): refused, done (MLink-26), or failed (MLink-114). */
public enum AuditOutcome {
    DENIED,
    SUCCESS,
    FAILURE
}
