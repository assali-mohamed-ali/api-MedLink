package com.example.apimedlink.model;

/** How the audited action ended (MLink-59): refused, or done (MLink-26). */
public enum AuditOutcome {
    DENIED,
    /** The action was carried out (MLink-104: a booking; MLink-31: a record read). */
    SUCCESS
}
