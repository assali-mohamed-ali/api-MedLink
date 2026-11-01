package com.example.apimedlink.model;

/** How the audited action ended (MLink-59). ERROR is reserved for a failed operation worth recording. */
public enum AuditOutcome {
    DENIED,
    SUCCESS,
    ERROR
}
