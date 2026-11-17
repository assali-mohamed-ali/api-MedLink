package com.example.apimedlink.model;

/** How the audited action ended (MLink-59): refused, or done (MLink-26). */
public enum AuditOutcome {
    DENIED,
    SUCCESS,
    FAILURE,
    /** The action was not performed because it was not needed (MLink-26). */
    NOT_NEEDED
}
