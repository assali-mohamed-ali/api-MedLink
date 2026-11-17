package com.example.apimedlink.model;

/** How the audited action ended (MLink-59): refused, or done (MLink-26). */
public enum AuditOutcome {
    DENIED,
    SUCCESS,
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
