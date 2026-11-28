package com.example.apimedlink.model;

/** US-16: where a deletion request stands. Only the administrative staff move it out of PENDING. */
public enum DeletionStatus {
    PENDING,
    /** Staff approved it; the purge itself is not implemented yet. */
    APPROVED,
    /** Staff refused it, with a note the patient can read. */
    REJECTED
}
