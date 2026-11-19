package com.example.apimedlink.model;

/** How the audited action ended (MLink-59): refused, or done (MLink-26); ERROR for a failed operation worth recording. */
public enum AuditOutcome {
    DENIED,
    /** The action was carried out (MLink-104: a booking; MLink-31: a record read). */
    SUCCESS,
    /** A failed operation worth recording (epic MLink-9). */
    ERROR,
    /** The action was attempted and failed (MLink-114). */
    FAILURE,
    /** The action was not performed because it was not needed (MLink-26). */
    NOT_NEEDED,
    /**
     * The action was asked for and is about to be carried out (MLink-72). It is written, and committed, before
     * anything outside our database is touched; a SUCCESS or FAILURE entry for the same resource closes it. An
     * ATTEMPTED entry with no closing entry therefore shows an operation that must be checked.
     */
    ATTEMPTED
}
